job "kith-pr-__PR_NUMBER__" {
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

    ephemeral_disk {
      size    = 500
      migrate = false
      sticky  = false
    }

    task "kith" {
      driver = "docker"

      config {
        image = "__IMAGE_TAG__"
        ports = ["http"]
      }

      env {
        KITH_DB_PATH        = "/alloc/data/kith.db"
        KITH_DATA_DIR       = "/alloc/data"
        KITH_BASE_URL       = "https://pr-__PR_NUMBER__.preview.fold.chat"
        KITH_DEV            = "false"
        KITH_ADMIN_USERNAME = "admin"
        KITH_ADMIN_PASSWORD = "preview-admin-pw"
        KITH_PORT           = "8080"
        JAVA_OPTS           = "--enable-native-access=ALL-UNNAMED"
      }

      resources {
        cpu    = 300
        memory = 512
      }

      service {
        name = "kith-pr-__PR_NUMBER__"
        port = "http"

        tags = [
          "traefik.enable=true",
          "traefik.http.routers.kith-pr-__PR_NUMBER__.rule=Host(`pr-__PR_NUMBER__.preview.fold.chat`)",
          "traefik.http.routers.kith-pr-__PR_NUMBER__.entrypoints=websecure",
          "traefik.http.routers.kith-pr-__PR_NUMBER__.tls=true",
        ]

        check {
          type     = "http"
          path     = "/api/v0/status"
          interval = "15s"
          timeout  = "5s"

          check_restart {
            limit           = 3
            grace           = "60s"
            ignore_warnings = true
          }
        }
      }
    }
  }
}
