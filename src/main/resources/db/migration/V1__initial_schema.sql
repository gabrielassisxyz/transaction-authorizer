CREATE TABLE accounts (
    id            UUID PRIMARY KEY,
    owner_id      UUID        NOT NULL,
    status        TEXT        NOT NULL CHECK (status IN ('ENABLED', 'DISABLED')),
    created_at    TIMESTAMPTZ NOT NULL,
    balance_cents BIGINT      NOT NULL DEFAULT 0 CHECK (balance_cents >= 0)
);

-- Inserted before any balance mutation in the authorization transaction, so the
-- primary key serializes duplicates up front instead of relying on rollback to undo
-- work already done. request_hash tells a genuine retry from an id collision.
CREATE TABLE transaction_claims (
    id           UUID        PRIMARY KEY,
    request_hash CHAR(64)    NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    id            UUID PRIMARY KEY,
    account_id    UUID        NOT NULL REFERENCES accounts (id),
    type          TEXT        NOT NULL CHECK (type IN ('CREDIT', 'DEBIT')),
    amount_cents  BIGINT      NOT NULL CHECK (amount_cents > 0),
    currency      CHAR(3)     NOT NULL CHECK (currency = 'BRL'),
    result        TEXT        NOT NULL CHECK (result IN ('SUCCEEDED', 'FAILED')),
    balance_after BIGINT      NOT NULL CHECK (balance_after >= 0),
    -- Application-generated, never DEFAULT now(): the API returns the exact stored
    -- timestamp and an injected clock keeps tests deterministic.
    created_at    TIMESTAMPTZ NOT NULL
);
