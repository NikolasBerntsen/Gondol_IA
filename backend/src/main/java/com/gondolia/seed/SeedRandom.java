package com.gondolia.seed;

import java.util.List;
import java.util.SplittableRandom;

/** Azar determinístico de la simulación (misma semilla → mismos datos) con las distribuciones que usa. */
final class SeedRandom {

    private final SplittableRandom random;

    SeedRandom(long seed) {
        this.random = new SplittableRandom(seed);
    }

    SeedRandom split() {
        return new SeedRandom(random.nextLong());
    }

    double nextDouble() {
        return random.nextDouble();
    }

    boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    /** Entero uniforme en {@code [min, max]}. */
    int between(int min, int max) {
        return max <= min ? min : min + random.nextInt(max - min + 1);
    }

    double gaussian() {
        double u1 = Math.max(1e-12, random.nextDouble());
        double u2 = random.nextDouble();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    /** Poisson (Knuth para medias chicas, aproximación normal para grandes). */
    int poisson(double lambda) {
        if (lambda <= 0) {
            return 0;
        }
        if (lambda > 30) {
            return Math.max(0, (int) Math.round(lambda + Math.sqrt(lambda) * gaussian()));
        }
        double limit = Math.exp(-lambda);
        double product = random.nextDouble();
        int count = 0;
        while (product > limit) {
            product *= random.nextDouble();
            count++;
        }
        return count;
    }

    <T> T pick(List<T> values) {
        return values.get(random.nextInt(values.size()));
    }

    /** Índice elegido según pesos relativos. */
    int weighted(double[] weights) {
        double total = 0;
        for (double weight : weights) {
            total += weight;
        }
        double target = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            target -= weights[i];
            if (target < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    char letter(String alphabet) {
        return alphabet.charAt(random.nextInt(alphabet.length()));
    }
}
