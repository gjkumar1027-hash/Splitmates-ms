--liquibase formatted sql

-- SplitMates core schema. All money columns are BIGINT paise (BR-01).

--changeset splitmates:001-users
CREATE TABLE users (
    id             BIGSERIAL PRIMARY KEY,
    display_name   VARCHAR(60)  NOT NULL,
    phone          VARCHAR(20)  UNIQUE,
    email          VARCHAR(255) UNIQUE,
    avatar_emoji   VARCHAR(16),
    avatar_color   VARCHAR(9)   NOT NULL DEFAULT '#7C3AED',
    upi_id         VARCHAR(100),
    theme          VARCHAR(10)  NOT NULL DEFAULT 'SYSTEM',
    notify_in_app  BOOLEAN      NOT NULL DEFAULT TRUE,
    notify_weekly  BOOLEAN      NOT NULL DEFAULT FALSE,
    show_phone     BOOLEAN      NOT NULL DEFAULT FALSE,
    consent_at     TIMESTAMPTZ,
    deleted        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
--rollback DROP TABLE users;

--changeset splitmates:001-otp-codes
CREATE TABLE otp_codes (
    id          BIGSERIAL PRIMARY KEY,
    phone       VARCHAR(20) NOT NULL,
    code_hash   VARCHAR(64) NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    consumed    BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_phone_created ON otp_codes (phone, created_at);
--rollback DROP TABLE otp_codes;

--changeset splitmates:001-refresh-tokens
CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE refresh_tokens;

--changeset splitmates:001-expense-groups
CREATE TABLE expense_groups (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(60) NOT NULL,
    type         VARCHAR(10) NOT NULL,
    cover_emoji  VARCHAR(16) NOT NULL DEFAULT '✈️',
    cover_color  VARCHAR(9)  NOT NULL DEFAULT '#7C3AED',
    start_date   DATE,
    end_date     DATE,
    currency     VARCHAR(3)  NOT NULL DEFAULT 'INR',
    status       VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    invite_code  VARCHAR(16) NOT NULL UNIQUE,
    created_by   BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at  TIMESTAMPTZ
);
--rollback DROP TABLE expense_groups;

--changeset splitmates:001-group-members
CREATE TABLE group_members (
    id            BIGSERIAL PRIMARY KEY,
    group_id      BIGINT      NOT NULL REFERENCES expense_groups (id) ON DELETE CASCADE,
    user_id       BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    display_name  VARCHAR(60) NOT NULL,
    avatar_emoji  VARCHAR(16),
    avatar_color  VARCHAR(9)  NOT NULL DEFAULT '#0EA5E9',
    role          VARCHAR(10) NOT NULL DEFAULT 'MEMBER',
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    joined_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_group_member_user ON group_members (group_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_group_members_user ON group_members (user_id);
--rollback DROP TABLE group_members;

--changeset splitmates:001-expenses
CREATE TABLE expenses (
    id                  BIGSERIAL PRIMARY KEY,
    group_id            BIGINT       NOT NULL REFERENCES expense_groups (id) ON DELETE CASCADE,
    expense_date        DATE         NOT NULL,
    purpose             VARCHAR(100) NOT NULL,
    amount              BIGINT       NOT NULL CHECK (amount > 0),
    category            VARCHAR(20)  NOT NULL,
    split_type          VARCHAR(10)  NOT NULL,
    notes               VARCHAR(500),
    receipt_path        VARCHAR(255),
    created_by_member   BIGINT       REFERENCES group_members (id),
    updated_by_member   BIGINT       REFERENCES group_members (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted_at          TIMESTAMPTZ,
    version             INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_expenses_group_date ON expenses (group_id, is_deleted, expense_date DESC);
--rollback DROP TABLE expenses;

--changeset splitmates:001-expense-payers
CREATE TABLE expense_payers (
    id           BIGSERIAL PRIMARY KEY,
    expense_id   BIGINT NOT NULL REFERENCES expenses (id) ON DELETE CASCADE,
    member_id    BIGINT NOT NULL REFERENCES group_members (id),
    amount_paid  BIGINT NOT NULL CHECK (amount_paid >= 0)
);
CREATE INDEX idx_payers_expense ON expense_payers (expense_id);
--rollback DROP TABLE expense_payers;

--changeset splitmates:001-expense-shares
CREATE TABLE expense_shares (
    id           BIGSERIAL PRIMARY KEY,
    expense_id   BIGINT         NOT NULL REFERENCES expenses (id) ON DELETE CASCADE,
    member_id    BIGINT         NOT NULL REFERENCES group_members (id),
    share_value  NUMERIC(14, 4),
    amount_owed  BIGINT         NOT NULL CHECK (amount_owed >= 0)
);
CREATE INDEX idx_shares_expense ON expense_shares (expense_id);
--rollback DROP TABLE expense_shares;

--changeset splitmates:001-settlements
CREATE TABLE settlements (
    id                 BIGSERIAL PRIMARY KEY,
    group_id           BIGINT       NOT NULL REFERENCES expense_groups (id) ON DELETE CASCADE,
    from_member        BIGINT       NOT NULL REFERENCES group_members (id),
    to_member          BIGINT       NOT NULL REFERENCES group_members (id),
    amount             BIGINT       NOT NULL CHECK (amount > 0),
    method             VARCHAR(10)  NOT NULL,
    settled_on         DATE         NOT NULL,
    status             VARCHAR(10)  NOT NULL,
    note               VARCHAR(200),
    created_by_member  BIGINT       REFERENCES group_members (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmed_at       TIMESTAMPTZ,
    CHECK (from_member <> to_member)
);
CREATE INDEX idx_settlements_group ON settlements (group_id);
--rollback DROP TABLE settlements;

--changeset splitmates:001-activity-log
CREATE TABLE activity_log (
    id            BIGSERIAL PRIMARY KEY,
    group_id      BIGINT       NOT NULL REFERENCES expense_groups (id) ON DELETE CASCADE,
    actor_member  BIGINT       REFERENCES group_members (id),
    action        VARCHAR(30)  NOT NULL,
    entity_type   VARCHAR(20)  NOT NULL,
    entity_id     BIGINT,
    summary       VARCHAR(300) NOT NULL,
    before_json   TEXT,
    after_json    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_activity_group_created ON activity_log (group_id, created_at DESC);
--rollback DROP TABLE activity_log;

--changeset splitmates:001-notifications
CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    group_id    BIGINT       REFERENCES expense_groups (id) ON DELETE CASCADE,
    type        VARCHAR(30)  NOT NULL,
    message     VARCHAR(300) NOT NULL,
    link        VARCHAR(200),
    is_read     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications (user_id, is_read, created_at DESC);
--rollback DROP TABLE notifications;

--changeset splitmates:001-nudges
CREATE TABLE nudges (
    id           BIGSERIAL PRIMARY KEY,
    group_id     BIGINT      NOT NULL REFERENCES expense_groups (id) ON DELETE CASCADE,
    from_member  BIGINT      NOT NULL REFERENCES group_members (id),
    to_member    BIGINT      NOT NULL REFERENCES group_members (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_nudges_pair ON nudges (from_member, to_member, created_at DESC);
--rollback DROP TABLE nudges;
