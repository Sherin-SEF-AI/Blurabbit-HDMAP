# Blurabbit HD-map backend — cloud infrastructure (AWS skeleton).
# Provisions a managed Kubernetes cluster, a PostGIS-enabled managed Postgres, an object-storage
# bucket for raw artifacts, and a Kafka-compatible ingestion stream. Apply with real credentials in
# a CI/CD pipeline; this is a reviewable skeleton, not applied in the dev sandbox.

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

variable "region" { default = "ap-south-1" }      # NavIC/India data residency
variable "project" { default = "blurabbit-hdmap" }
variable "db_password" { sensitive = true }

provider "aws" { region = var.region }

# --- Networking ---------------------------------------------------------------------------------
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.0"
  name    = "${var.project}-vpc"
  cidr    = "10.0.0.0/16"
  azs             = ["${var.region}a", "${var.region}b", "${var.region}c"]
  private_subnets = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  public_subnets  = ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]
  enable_nat_gateway = true
}

# --- Kubernetes (EKS) — runs the backend + fusion workers (HPA/KEDA autoscaled) -----------------
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.0"
  cluster_name    = "${var.project}-eks"
  cluster_version = "1.30"
  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets
  eks_managed_node_groups = {
    workers = { min_size = 2, max_size = 20, desired_size = 3, instance_types = ["m6i.large"] }
  }
}

# --- Managed Postgres + PostGIS (feature store) -------------------------------------------------
resource "aws_db_instance" "postgis" {
  identifier            = "${var.project}-db"
  engine                = "postgres"
  engine_version        = "16"
  instance_class        = "db.r6g.large"
  allocated_storage     = 100
  max_allocated_storage = 1000
  db_name               = "blurabbit"
  username              = "blurabbit"
  password              = var.db_password
  multi_az              = true
  storage_encrypted     = true
  skip_final_snapshot   = false
  # Enable PostGIS after creation: CREATE EXTENSION postgis; (see sql/schema-postgis.sql)
}

# --- Object storage (raw per-trip artifacts + exports) ------------------------------------------
resource "aws_s3_bucket" "artifacts" {
  bucket = "${var.project}-artifacts"
}

# --- Ingestion stream (decouples ingest from fusion workers) ------------------------------------
resource "aws_msk_serverless_cluster" "ingest" {
  cluster_name = "${var.project}-ingest"
  vpc_config {
    subnet_ids         = module.vpc.private_subnets
    security_group_ids = [module.eks.cluster_primary_security_group_id]
  }
  client_authentication { sasl { iam { enabled = true } } }
}

output "eks_cluster_name" { value = module.eks.cluster_name }
output "db_endpoint" { value = aws_db_instance.postgis.endpoint }
output "artifacts_bucket" { value = aws_s3_bucket.artifacts.bucket }
