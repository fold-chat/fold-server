job "kith-main" {
  datacenters = ["dc1"]
  type        = "service"

  group "kith" {
    count = 1

    network {
      port "http" {
        to = 8080
      }
    }

    volume "kith-data" {
      type      = "host"
      source    = "kith-prod-data"
      read_only = false
    }

    task "kith" {
      driver = "docker"

      config {
        image = "__IMAGE_TAG__"
        ports = ["http"]
      }

      volume_mount {
        volume      = "kith-data"
        destination = "/persist"
        read_only   = false
      }

      env {
        KITH_DB_PATH      = "/persist/kith.db"
        KITH_DATA_DIR     = "/persist"
        KITH_BASE_URL     = "https://__KITH_DOMAIN__"
        KITH_CORS_ORIGINS = "https://__KITH_DOMAIN__"
        KITH_DEV          = "false"
        KITH_PORT         = "8080"
        KITH_LIVEKIT_MODE = "embedded"
        JAVA_OPTS         = "--enable-native-access=ALL-UNNAMED"
      }

      resources {
        cpu    = 500
        memory = 1024
      }

      service {
        provider = "nomad"
        name     = "kith-main"
        port     = "http"

        tags = [
          "traefik.enable=true",
          "traefik.http.routers.kith-main.rule=Host(`__KITH_DOMAIN__`)",
          "traefik.http.routers.kith-main.entrypoints=websecure",
          "traefik.http.routers.kith-main.tls=true",
        ]

        check {
          type     = "http"
          path     = "/api/v0/status"
          interval = "15s"
          timeout  = "5s"

          check_restart {
            limit = 3
            grace = "60s"
          }
        }
      }
    }
  }
}
