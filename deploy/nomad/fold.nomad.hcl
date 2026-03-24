job "fold-main" {
  namespace   = "ci"
  datacenters = ["dc1"]
  type        = "service"

  group "fold" {
    count = 1

    network {
      port "http" {
        to = 8080
      }
    }

    volume "fold-data" {
      type      = "host"
      source    = "fold-prod-data"
      read_only = false
    }

    task "fold" {
      driver = "docker"

      config {
        image = "__IMAGE_TAG__"
        ports = ["http"]
      }

      volume_mount {
        volume      = "fold-data"
        destination = "/persist"
        read_only   = false
      }

      env {
        FOLD_DB_PATH      = "/persist/fold.db"
        FOLD_DATA_DIR     = "/persist"
        FOLD_BASE_URL     = "https://__FOLD_DOMAIN__"
        FOLD_CORS_ORIGINS = "https://__FOLD_DOMAIN__"
        FOLD_DEV          = "false"
        FOLD_PORT         = "8080"
        FOLD_LIVEKIT_MODE        = "off"
        FOLD_LIVEKIT_WEBHOOK_URL = "https://__FOLD_DOMAIN__"
        JAVA_OPTS         = "-Xmx1536m -Xms512m --enable-native-access=ALL-UNNAMED"
      }

      template {
        destination = "${NOMAD_SECRETS_DIR}/klipy.env"
        env         = true
        error_on_missing_key = true
        data        = <<EOT
FOLD_KLIPY_API_KEY={{ with nomadVar "nomad/jobs" }}{{ index . "FOLD_KLIPY_API_KEY" | toJSON }}{{ end }}
EOT
      }

      resources {
        cpu    = 500
        memory = 2048
      }

      service {
        provider = "nomad"
        name     = "fold-main"
        port     = "http"

        tags = [
          "traefik.enable=true",
          "traefik.http.routers.fold-main.rule=Host(`__FOLD_DOMAIN__`)",
          "traefik.http.routers.fold-main.entrypoints=websecure",
          "traefik.http.routers.fold-main.tls=true",
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
