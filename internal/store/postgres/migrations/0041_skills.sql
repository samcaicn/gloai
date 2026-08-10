-- +goose Up
-- Skill marketplace: skills, per-version review flow, ratings and installs.

CREATE TABLE IF NOT EXISTS skills (
    id                TEXT PRIMARY KEY,
    slug              TEXT NOT NULL UNIQUE,
    name              TEXT NOT NULL,
    description       TEXT NOT NULL DEFAULT '',
    icon              TEXT NOT NULL DEFAULT '',
    category          TEXT NOT NULL DEFAULT '',
    tags              TEXT NOT NULL DEFAULT '',
    homepage          TEXT NOT NULL DEFAULT '',
    license           TEXT NOT NULL DEFAULT '',
    author            TEXT NOT NULL DEFAULT '',
    owner_id          TEXT NOT NULL DEFAULT '',
    source            TEXT NOT NULL DEFAULT 'upload',
    source_url        TEXT NOT NULL DEFAULT '',
    latest_version_id TEXT NOT NULL DEFAULT '',
    listing           TEXT NOT NULL DEFAULT 'draft',
    reject_reason     TEXT NOT NULL DEFAULT '',
    install_count     INTEGER NOT NULL DEFAULT 0,
    rating_sum        INTEGER NOT NULL DEFAULT 0,
    rating_count      INTEGER NOT NULL DEFAULT 0,
    created_at        BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::BIGINT),
    updated_at        BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::BIGINT)
);
CREATE INDEX IF NOT EXISTS idx_skills_listing ON skills(listing);
CREATE INDEX IF NOT EXISTS idx_skills_owner ON skills(owner_id);
CREATE INDEX IF NOT EXISTS idx_skills_category ON skills(category);

CREATE TABLE IF NOT EXISTS skill_versions (
    id             TEXT PRIMARY KEY,
    skill_id       TEXT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    version        TEXT NOT NULL,
    changelog      TEXT NOT NULL DEFAULT '',
    manifest       TEXT NOT NULL DEFAULT '{}',
    readme         TEXT NOT NULL DEFAULT '',
    entry          TEXT NOT NULL DEFAULT 'SKILL.md',
    bundle_key     TEXT NOT NULL DEFAULT '',
    bundle_url     TEXT NOT NULL DEFAULT '',
    bundle_size    BIGINT NOT NULL DEFAULT 0,
    bundle_sha256  TEXT NOT NULL DEFAULT '',
    files          TEXT NOT NULL DEFAULT '[]',
    source_url     TEXT NOT NULL DEFAULT '',
    commit_hash    TEXT NOT NULL DEFAULT '',
    status         TEXT NOT NULL DEFAULT 'pending',
    reject_reason  TEXT NOT NULL DEFAULT '',
    submitted_by   TEXT NOT NULL DEFAULT '',
    reviewed_by    TEXT NOT NULL DEFAULT '',
    reviewed_at    BIGINT NOT NULL DEFAULT 0,
    download_count INTEGER NOT NULL DEFAULT 0,
    created_at     BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::BIGINT),
    UNIQUE (skill_id, version)
);
CREATE INDEX IF NOT EXISTS idx_skill_versions_skill ON skill_versions(skill_id);
CREATE INDEX IF NOT EXISTS idx_skill_versions_status ON skill_versions(status);

CREATE TABLE IF NOT EXISTS skill_ratings (
    id         TEXT PRIMARY KEY,
    skill_id   TEXT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    user_id    TEXT NOT NULL,
    rating     INTEGER NOT NULL,
    comment    TEXT NOT NULL DEFAULT '',
    version    TEXT NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::BIGINT),
    updated_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::BIGINT),
    UNIQUE (skill_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_skill_ratings_skill ON skill_ratings(skill_id);

CREATE TABLE IF NOT EXISTS skill_installs (
    id         TEXT PRIMARY KEY,
    skill_id   TEXT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    version_id TEXT NOT NULL DEFAULT '',
    user_id    TEXT NOT NULL,
    agent_id   TEXT NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::BIGINT),
    UNIQUE (skill_id, user_id, agent_id)
);
CREATE INDEX IF NOT EXISTS idx_skill_installs_user ON skill_installs(user_id);

CREATE TABLE IF NOT EXISTS skill_reviews (
    id         TEXT PRIMARY KEY,
    skill_id   TEXT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    version_id TEXT NOT NULL DEFAULT '',
    action     TEXT NOT NULL,
    actor_id   TEXT NOT NULL DEFAULT '',
    reason     TEXT NOT NULL DEFAULT '',
    version    TEXT NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::BIGINT)
);
CREATE INDEX IF NOT EXISTS idx_skill_reviews_skill ON skill_reviews(skill_id);

-- +goose Down
DROP TABLE IF EXISTS skill_reviews;
DROP TABLE IF EXISTS skill_installs;
DROP TABLE IF EXISTS skill_ratings;
DROP TABLE IF EXISTS skill_versions;
DROP TABLE IF EXISTS skills;
