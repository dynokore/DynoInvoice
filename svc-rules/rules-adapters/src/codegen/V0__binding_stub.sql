-- CODE GENERATION ONLY: this file is copied next to the production migrations into the throwaway code-generation
-- database (rules-adapters/build.gradle.kts, prepareCodegenMigrations) and is never part of the service's migrations.
-- The policies of V1 name rules.bound_tenant() and rules.bound_actor(); in an installation tenancy-kernel's
-- BindingKeyMigration creates them as the sys login (precondition, owner question INV-Q-6580), which an init script
-- cannot do (INV-Q-4457). The stubs let the policies be created; they change no table shape.
CREATE FUNCTION rules.bound_tenant() RETURNS uuid LANGUAGE sql STABLE AS 'select null::uuid';
CREATE FUNCTION rules.bound_actor() RETURNS text LANGUAGE sql STABLE AS 'select null::text';
