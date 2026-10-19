-- Init script of gst.jooq-codegen (INV-T-4223, INV-Q-4457): run as the container superuser before the migrations. It
-- creates the roles the migrations grant to and nothing else.
CREATE ROLE rules_migrate NOLOGIN;
CREATE ROLE rules_runtime NOLOGIN;
CREATE ROLE rules_sys NOLOGIN;
