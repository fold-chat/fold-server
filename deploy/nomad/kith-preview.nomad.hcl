job "kith-pr-__PR_NUMBER__" {
  namespace   = "ci"
  datacenters = ["dc1"]
  type        = "service"

  meta {
    pr_number = "__PR_NUMBER__"
  }

  group "kith" {
    count = 1

    network {
      port "http" {
        to = 8080
      }
    }
    volume "preview-data" {
      type      = "host"
      source    = "kith-preview-data"
      read_only = false
    }

    task "kith" {
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
        KITH_DB_PATH        = "/persist/pr-__PR_NUMBER__/kith.db"
        KITH_DATA_DIR       = "/persist/pr-__PR_NUMBER__"
        KITH_BASE_URL       = "https://pr-__PR_NUMBER__-preview.fold.chat"
        KITH_CORS_ORIGINS   = "https://pr-__PR_NUMBER__-preview.fold.chat"
        KITH_DEV            = "false"
        KITH_ADMIN_USERNAME = "admin"
        KITH_ADMIN_PASSWORD = "preview-admin-pw"
        KITH_PORT           = "8080"
        KITH_LIVEKIT_MODE   = "off"
        JAVA_OPTS           = "--enable-native-access=ALL-UNNAMED -Djava.security.egd=file:/dev/./urandom"
      }

      template {
        destination = "${NOMAD_SECRETS_DIR}/klipy.env"
        env         = true
        error_on_missing_key = true
        data        = <<EOT
KITH_KLIPY_API_KEY={{ with nomadVar "nomad/jobs" }}{{ index . "KITH_KLIPY_API_KEY" | toJSON }}{{ end }}
EOT
      }

      resources {
        cpu    = 300
        memory = 192
      }

      service {
        provider = "nomad"
        name     = "kith-pr-__PR_NUMBER__"
        port     = "http"

        tags = [
          "traefik.enable=true",
          "traefik.http.routers.kith-pr-__PR_NUMBER__.rule=Host(`pr-__PR_NUMBER__-preview.fold.chat`)",
          "traefik.http.routers.kith-pr-__PR_NUMBER__.entrypoints=websecure",
          "traefik.http.routers.kith-pr-__PR_NUMBER__.tls=true",
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
