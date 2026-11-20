-- Numéro de version des sessions d'un compte : il augmente au changement de mot de passe (ou de double
-- authentification), ce qui invalide toutes les sessions ouvertes avec l'ancien numéro.
ALTER TABLE users ADD COLUMN session_version INTEGER NOT NULL DEFAULT 0;

-- Double authentification (TOTP, compatible Google Authenticator) : secret chiffré, vide si désactivée.
ALTER TABLE users ADD COLUMN totp_secret TEXT;
-- Dernier code accepté (numéro de tranche de 30 s) : un même code ne peut pas servir deux fois.
ALTER TABLE users ADD COLUMN totp_last_step BIGINT NOT NULL DEFAULT 0;
