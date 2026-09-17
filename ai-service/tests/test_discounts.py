import pytest

from app.analysis.discounts import DEFAULT_ELASTICITY, NO_CATEGORY, ElasticityModel, observation_from_feedback
from app.schemas import FeedbackItem


def feedback(category="Lácteos", pct=20, lift=None, before=10, after=18, status="ACCEPTED", kind="DISCOUNT"):
    outcome = {"unitsBefore7d": before, "unitsAfter7d": after}
    if lift is not None:
        outcome["lift"] = lift
    return FeedbackItem.model_validate(
        {"recommendationId": 1, "type": kind, "productId": 10, "category": category, "status": status, "discountPct": pct, "outcome": outcome}
    )


def test_default_elasticity_without_feedback():
    model = ElasticityModel.from_feedback([])
    assert model.for_category("Lácteos") == DEFAULT_ELASTICITY == 2.0
    assert model.by_category({"Lácteos", None}) == {"Lácteos": 2.0, NO_CATEGORY: 2.0}


def test_observed_elasticity_is_lift_minus_one_over_discount():
    observation = observation_from_feedback(feedback(pct=20, lift=1.8))
    assert observation.elasticity == pytest.approx(4.0)
    assert observation.weight == pytest.approx(28)


def test_lift_is_derived_from_units_when_missing():
    observation = observation_from_feedback(feedback(pct=25, before=10, after=15))
    assert observation.elasticity == pytest.approx(2.0)


def test_successful_discounts_raise_the_category_elasticity():
    model = ElasticityModel.from_feedback([feedback(pct=20, lift=1.8)])
    value = model.for_category("Lácteos")
    assert DEFAULT_ELASTICITY < value < 4.0
    assert value == pytest.approx((20 * 2.0 + 28 * 4.0) / 48)


def test_discounts_that_did_not_move_sales_lower_the_elasticity():
    model = ElasticityModel.from_feedback([feedback(pct=20, lift=1.02, before=80, after=82)] * 2)
    assert model.for_category("Lácteos") < 0.5


def test_more_evidence_moves_the_estimate_further():
    one = ElasticityModel.from_feedback([feedback(pct=20, lift=1.8)]).for_category("Lácteos")
    three = ElasticityModel.from_feedback([feedback(pct=20, lift=1.8)] * 3).for_category("Lácteos")
    assert DEFAULT_ELASTICITY < one < three < 4.0


def test_other_categories_borrow_evidence_with_more_caution():
    model = ElasticityModel.from_feedback([feedback(category="Lácteos", pct=20, lift=1.8)])
    dairy = model.for_category("Lácteos")
    drinks = model.for_category("Bebidas")
    assert DEFAULT_ELASTICITY < drinks < dairy
    assert set(model.by_category({"Bebidas"})) == {"Bebidas", "Lácteos"}


@pytest.mark.parametrize(
    "item",
    [
        feedback(status="DISCARDED", lift=1.8),
        feedback(kind="REORDER", lift=1.8),
        feedback(pct=0, lift=1.8),
        FeedbackItem.model_validate({"type": "DISCOUNT", "status": "ACCEPTED", "discountPct": 20}),
        feedback(lift=None, before=0, after=5),
    ],
)
def test_unusable_feedback_is_ignored(item):
    assert observation_from_feedback(item) is None


def test_elasticity_is_clamped_to_a_sane_range():
    assert observation_from_feedback(feedback(pct=10, lift=5.0)).elasticity == 8.0
    assert observation_from_feedback(feedback(pct=10, lift=0.5)).elasticity == 0.0


def test_acceptance_rate_adjusts_confidence():
    accepted = [feedback(status="ACCEPTED", lift=1.5)] * 4
    discarded = [feedback(status="DISCARDED")] * 6
    assert ElasticityModel.from_feedback(accepted[:2]).acceptance_factor("DISCOUNT") == 1.0
    assert ElasticityModel.from_feedback(accepted).acceptance_factor("DISCOUNT") > 1.0
    assert ElasticityModel.from_feedback(discarded).acceptance_factor("DISCOUNT") < 1.0
