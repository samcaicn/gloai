FROM golang:1.27.3-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
COPY web ./web
RUN go test ./... \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/mika-relay . \
    && mkdir -p /out/data && chown 10001:10001 /out/data
USER 10001:10001
RUN /out/mika-relay -init-owner-key -owner-key-file /out/data/build-check-key \
    && rm /out/data/build-check-key

FROM caddy:2.11.7-alpine AS caddy-check
ENV MIKA_DOMAIN=mika.example.com
COPY Caddyfile /etc/caddy/Caddyfile
RUN caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

FROM scratch
COPY --from=build /out/mika-relay /mika-relay
COPY --from=build --chown=10001:10001 /out/data /data
COPY LICENSE.coder-websocket.txt /licenses/coder-websocket.txt
COPY LICENSE NOTICE /licenses/
USER 10001:10001
VOLUME ["/data"]
EXPOSE 8787
ENTRYPOINT ["/mika-relay"]
