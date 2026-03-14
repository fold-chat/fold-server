job "fold-pr-__PR_NUMBER__" {
  namespace   = "ci"
  datacenters = ["dc1"]
  type        = "service"

  meta {
    pr_number = "__PR_NUMBER__"
  }

  group "fold" {
    count = 1

    network {
      port "http" {
        to = 8080
      }
    }
    volume "preview-data" {
      type      = "host"
      source    = "fold-preview-data"
      read_only = false
    }

    task "fold" {
      driver = "docker"

      config {
        image = "__IMAGE_TAG__"
        ports = ["http"]
      }

      volume_mount {
        volume      = "preview-data"
        destination = "/persist"
        read_only   = false
      }

      env {
        FOLD_DB_PATH        = "/persist/pr-__PR_NUMBER__/fold.db"
        FOLD_DATA_DIR       = "/persist/pr-__PR_NUMBER__"
        FOLD_BASE_URL       = "https://pr-__PR_NUMBER__-preview.fold.chat"
        FOLD_CORS_ORIGINS   = "https://pr-__PR_NUMBER__-preview.fold.chat"
        FOLD_DEV            = "false"
        FOLD_ADMIN_USERNAME = "admin"
        FOLD_ADMIN_PASSWORD = "preview-admin-pw"
        FOLD_PORT           = "8080"
        FOLD_LIVEKIT_MODE        = "off"
        FOLD_LIVEKIT_WEBHOOK_URL = "https://pr-__PR_NUMBER__-preview.fold.chat"
        JAVA_OPTS           = "--enable-native-access=ALL-UNNAMED -Djava.security.egd=file:/dev/./urandom"
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
        cpu    = 300
        memory = 192
      }

      service {
        provider = "nomad"
        name     = "fold-pr-__PR_NUMBER__"
        port     = "http"

        tags = [
          "traefik.enable=true",
          "traefik.http.routers.fold-pr-__PR_NUMBER__.rule=Host(`pr-__PR_NUMBER__-preview.fold.chat`)",
          "traefik.http.routers.fold-pr-__PR_NUMBER__.entrypoints=websecure",
          "traefik.http.routers.fold-pr-__PR_NUMBER__.tls=true",
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
