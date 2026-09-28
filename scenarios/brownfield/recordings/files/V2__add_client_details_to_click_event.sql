-- Keep request details so unique visitors can be counted with COUNT(DISTINCT client_ip, user_agent).
ALTER TABLE click_event ADD COLUMN client_ip VARCHAR(45) NULL;
ALTER TABLE click_event ADD COLUMN user_agent VARCHAR(512) NULL;
