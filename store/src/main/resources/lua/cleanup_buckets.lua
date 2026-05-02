local bucketCount = #KEYS - 2
local metaKey = KEYS[bucketCount + 1]
local totalRemainingKey = KEYS[bucketCount + 2]

for i = 1, bucketCount do
    redis.call('DEL', KEYS[i])
end
redis.call('DEL', metaKey)
redis.call('DEL', totalRemainingKey)
return 1
