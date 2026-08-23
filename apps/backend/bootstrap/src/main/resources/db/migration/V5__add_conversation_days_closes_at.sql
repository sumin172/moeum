ALTER TABLE conversation.conversation_days ADD COLUMN closes_at TIMESTAMPTZ NULL;

-- 기존 row 백필: 그 row의 timezone 기준으로 local_date 다음날 자정에 해당하는 UTC 시각.
UPDATE conversation.conversation_days
SET closes_at = (local_date + INTERVAL '1 day') AT TIME ZONE timezone
WHERE closes_at IS NULL;

ALTER TABLE conversation.conversation_days ALTER COLUMN closes_at SET NOT NULL;

-- 마감 배치가 'WHERE status=OPEN AND closes_at<=now()'로 스캔하므로 OPEN row만 담는 partial index로 충분하다.
CREATE INDEX idx_conversation_conversation_days_open_closes_at
    ON conversation.conversation_days (closes_at)
    WHERE status = 'OPEN';
