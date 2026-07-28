FROM golang:1.26.5-alpine AS build
WORKDIR /src
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/harness-relay ./cmd/relay

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/harness-relay /harness-relay
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["/harness-relay", "--state", "/data/relay.json"]
