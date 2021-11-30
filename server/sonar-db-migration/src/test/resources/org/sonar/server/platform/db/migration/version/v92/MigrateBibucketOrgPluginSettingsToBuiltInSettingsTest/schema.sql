CREATE TABLE "PROPERTIES"(
    "UUID" VARCHAR(40) NOT NULL,
    "PROP_KEY" VARCHAR(512) NOT NULL,
    "IS_EMPTY" BOOLEAN NOT NULL,
    "TEXT_VALUE" VARCHAR(4000),
    "CLOB_VALUE" CLOB,
    "CREATED_AT" BIGINT NOT NULL,
    "COMPONENT_UUID" VARCHAR(40),
    "USER_UUID" VARCHAR(255)
);
ALTER TABLE "PROPERTIES" ADD CONSTRAINT "PK_PROPERTIES" PRIMARY KEY("UUID");
CREATE INDEX "PROPERTIES_KEY" ON "PROPERTIES"("PROP_KEY");