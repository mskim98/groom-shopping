-- PostgreSQL database setup script for dev environment
-- Run this script as a PostgreSQL superuser (usually 'postgres')

-- Create role/user if it doesn't exist
DO $$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_catalog.pg_user WHERE usename = 'dev') THEN
      CREATE ROLE dev WITH LOGIN PASSWORD 'dev123';
   END IF;
END
$$;

-- Create database if it doesn't exist
SELECT 'CREATE DATABASE shopping_db_dev OWNER dev'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'shopping_db_dev')\gexec

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE shopping_db_dev TO dev;

-- Connect to the new database and grant schema privileges
\c shopping_db_dev
GRANT ALL ON SCHEMA public TO dev;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO dev;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO dev;

