-- Sliding-window counter, atomic inside Redis.
-- KEYS[1] = window key (one per customer)
-- ARGV[1] = window length in milliseconds, ARGV[2] = unique id for this attempt
-- Returns how many attempts (including this one) happened inside the window.
local key = KEYS[1]
local window_ms = tonumber(ARGV[1])
local member = ARGV[2]

-- Redis' own clock, so several app servers agree on "now"
local t = redis.call('TIME')
local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

redis.call('ZREMRANGEBYSCORE', key, 0, now_ms - window_ms)   -- forget attempts older than the window
redis.call('ZADD', key, now_ms, member)
redis.call('PEXPIRE', key, window_ms)                         -- idle customers clean themselves up
return redis.call('ZCARD', key)
