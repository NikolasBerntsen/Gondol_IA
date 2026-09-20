import { afterEach, describe, expect, it, vi } from 'vitest';
import { LOCAL_CA_CERT_PATH, getSecureUrl, isCameraSupported, isLocalhost, needsSecureContextForCamera } from './secureContext';

/** jsdom deja cambiar location con una redefinición del objeto. */
function setLocation(url: string) {
  const parsed = new URL(url);
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: {
      hostname: parsed.hostname,
      pathname: parsed.pathname,
      search: parsed.search,
      hash: parsed.hash,
      href: parsed.href,
    },
  });
}

function setSecureContext(secure: boolean) {
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: secure });
}

const originalLocation = window.location;

afterEach(() => {
  Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
  setSecureContext(true);
  vi.unstubAllGlobals();
});

describe('isLocalhost', () => {
  it('reconoce los nombres locales', () => {
    expect(isLocalhost('localhost')).toBe(true);
    expect(isLocalhost('127.0.0.1')).toBe(true);
    expect(isLocalhost('::1')).toBe(true);
    expect(isLocalhost('[::1]')).toBe(true);
    expect(isLocalhost('mi-pc.localhost')).toBe(true);
  });

  it('una IP de la red no es local', () => {
    expect(isLocalhost('192.168.0.15')).toBe(false);
    expect(isLocalhost('gondolia.144-22-138-149.sslip.io')).toBe(false);
  });

  it('sin argumento mira el host de la página', () => {
    setLocation('http://192.168.0.15:8080/app');
    expect(isLocalhost()).toBe(false);
  });
});

describe('cámara', () => {
  it('necesita contexto seguro y la API de medios', () => {
    setSecureContext(true);
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: () => undefined } });
    expect(isCameraSupported()).toBe(true);

    vi.stubGlobal('navigator', {});
    expect(isCameraSupported()).toBe(false);

    setSecureContext(false);
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: () => undefined } });
    expect(isCameraSupported()).toBe(false);
  });

  it('por HTTP desde otra máquina hay que pasar a HTTPS', () => {
    setSecureContext(false);
    setLocation('http://192.168.0.15:8080/app/intake');
    expect(needsSecureContextForCamera()).toBe(true);
  });

  it('en localhost por HTTP la cámara no está bloqueada por el contexto', () => {
    setSecureContext(false);
    setLocation('http://localhost:8080/app/intake');
    expect(needsSecureContextForCamera()).toBe(false);
  });
});

describe('getSecureUrl', () => {
  it('arma la misma URL en HTTPS con el puerto de nginx', () => {
    setLocation('http://192.168.0.15:8080/app/intake?scan=1#lote');
    expect(getSecureUrl()).toBe('https://192.168.0.15:8443/app/intake?scan=1#lote');
  });

  it('encierra las IPv6 entre corchetes', () => {
    setLocation('http://[fe80::1]:8080/app');
    // window.location.hostname de jsdom devuelve la IPv6 sin corchetes.
    expect(getSecureUrl()).toMatch(/^https:\/\/\[fe80::1\]:8443\/app$/);
  });
});

describe('LOCAL_CA_CERT_PATH', () => {
  it('coincide con la ruta que publica nginx', () => {
    expect(LOCAL_CA_CERT_PATH).toBe('/gondolia-ca.crt');
  });
});
