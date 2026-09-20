# GondolIA - frontend (React + Vite) servido por nginx con HTTPS local.

# ---------- Build ----------
FROM node:22-alpine AS build
WORKDIR /app

# Capa de dependencias: solo se reinstala si cambian package.json o package-lock.json.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---------- Runtime ----------
FROM nginx:1.27-alpine

# openssl: genera la CA local y el certificado del servidor al iniciar (docker-entrypoint.d).
# worker_processes 2: sobra para una red local y evita un proceso por núcleo de la PC.
# load_module: njs (ya viene en la imagen oficial) para el timestamp UTC de los errores que genera nginx en /api/
# (njs/gondolia-errors.js). Va en el contexto main, por eso se agrega acá y no en templates/gondolia.conf.template.
RUN apk add --no-cache openssl \
    && rm -f /etc/nginx/conf.d/default.conf \
    && sed -i 's/^worker_processes[[:space:]].*/worker_processes 2;/' /etc/nginx/nginx.conf \
    && test -f /usr/lib/nginx/modules/ngx_http_js_module.so \
    && sed -i '1i load_module modules/ngx_http_js_module.so;' /etc/nginx/nginx.conf \
    && mkdir -p /etc/nginx/certs /etc/nginx/snippets /etc/nginx/njs

COPY nginx/templates/ /etc/nginx/templates/
COPY nginx/snippets/ /etc/nginx/snippets/
COPY nginx/njs/ /etc/nginx/njs/
COPY nginx/docker-entrypoint.d/ /docker-entrypoint.d/

# Defensa ante checkouts de Windows con CRLF: los scripts y las configs tienen que quedar con LF.
RUN find /docker-entrypoint.d /etc/nginx/templates /etc/nginx/snippets /etc/nginx/njs -type f -name '*gondolia*' \
        -exec sed -i 's/\r$//' {} + \
    && chmod +x /docker-entrypoint.d/*.sh

COPY --from=build /app/dist /usr/share/nginx/html

# HTTPS_PORT: puerto HTTPS publicado en el host (para las redirecciones a HTTPS).
# LAN_IPS / CERT_HOSTNAMES: nombres extra del certificado (los completa start.sh vía docker compose).
# NGINX_ENTRYPOINT_LOCAL_RESOLVERS: exporta NGINX_LOCAL_RESOLVERS con el DNS interno de Docker.
ENV HTTPS_PORT=8443 \
    LAN_IPS="" \
    CERT_HOSTNAMES="" \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    NGINX_ENVSUBST_FILTER="^(HTTPS_PORT|NGINX_LOCAL_RESOLVERS)$"

EXPOSE 80 443

HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=5 \
    CMD wget -q -T 4 -O /dev/null http://127.0.0.1/nginx-health || exit 1
