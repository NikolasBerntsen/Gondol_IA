import { apiGet, apiPost } from './client';
import type { ChangePasswordRequest, LoginRequest, LoginResponse, MeDto, TokenResponse } from './types';

export const authApi = {
  login: (body: LoginRequest) => apiPost<LoginResponse>('/auth/login', body, { skipAuthHandling: true }),

  me: () => apiGet<MeDto>('/auth/me'),

  /** El backend devuelve un token nuevo (incrementa `token_version`); puede venir vacío si responde 204. */
  changePassword: (body: ChangePasswordRequest) =>
    apiPost<TokenResponse | '' | null>('/auth/change-password', body, { skipAuthHandling: true }),
};
