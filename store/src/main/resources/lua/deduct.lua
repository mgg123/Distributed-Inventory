local current = tonumber(redis.call('GET', KEYS[1]) or '0')
local total = tonumber(redis.call('GET', KEYS[2]) or '0')
local quantity = tonumber(ARGV[1])
if current >= quantity and total >= quantity then
    redis.call('DECRBY', KEYS[1], quantity)
    local remaining = redis.call('DECRBY', KEYS[2], quantity)
    if tonumber(remaining) <= 0 then
        return 2
    end
    return 1
else
    return 0
end
