variable "pr_number" {
  type = string
}

variable "image_tag" {
  type    = string
  default = ""
}

variable "domain" {
  type    = string
  default = "fold.chat"
}

locals {
  image = var.image_tag != "" ? var.image_tag : "ghcr.io/fray-chat/fray-app:pr-${var.pr_number}"
}

job "kith-pr-${var.pr_number}" {
  datacenters = ["dc1"]
  type        = "service"

  meta {
    pr_number = var.pr_number
  }

  group "kith" {
    count = 1

    network {
      port "http" {
        to = 8080
      }
    }

    ephemeral_disk {
      size    = 500  # MB
      migrate = false
      sticky  = false
    }

    task "kith" {
      driver = "docker"

      config {
        image = local.image
        ports = ["http"]
      }

      env {
        KITH_DB_PATH        = "/alloc/data/kith.db"
        KITH_DATA_DIR       = "/alloc/data"
        KITH_BASE_URL       = "https://pr-${var.pr_number}.preview.${var.domain}"
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
        name = "kith-pr-${var.pr_number}"
        port = "http"

        tags = [
          "traefik.enable=true",
          "traefik.http.routers.kith-pr-${var.pr_number}.rule=Host(`pr-${var.pr_number}.preview.${var.domain}`)",
          "traefik.http.routers.kith-pr-${var.pr_number}.entrypoints=websecure",
          "traefik.http.routers.kith-pr-${var.pr_number}.tls=true",
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
