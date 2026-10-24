-- Token bucket, executed atomically inside Redis.
-- KEYS[1] = bucket key
-- ARGV[1] = capacity (max burst), ARGV[2] = refill tokens per second
-- Returns {allowed (1/0), tokens remaining, retry_after_ms}
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_sec = tonumber(ARGV[2])

-- use Redis' own clock so several app instances can't disagree about the time
local t = redis.call('TIME')
local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local data = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])
if tokens == nil then
  tokens = capacity
  ts = now_ms
end

local elapsed = math.max(0, now_ms - ts)
tokens = math.min(capacity, tokens + (elapsed / 1000.0) * refill_per_sec)

local allowed = 0
local retry_after_ms = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry_after_ms = math.ceil((1 - tokens) / refill_per_sec * 1000)
end

redis.call('HSET', key, 'tokens', tokens, 'ts', now_ms)
-- idle buckets clean themselves up after twice the time needed to fully refill
redis.call('PEXPIRE', key, math.ceil(capacity / refill_per_sec * 1000) * 2)

return {allowed, math.floor(tokens), retry_after_ms}
