local bucketCount = #KEYS - 2
local metaKey = KEYS[bucketCount + 1]
local totalRemainingKey = KEYS[bucketCount + 2]
local quantityPerBucket = tonumber(ARGV[1])
local metaValue = ARGV[2]

for i = 1, bucketCount do
    redis.call('SET', KEYS[i], quantityPerBucket)
end
redis.call('SET', metaKey, metaValue)
redis.call('SET', totalRemainingKey, bucketCount * quantityPerBucket)
return bucketCount
