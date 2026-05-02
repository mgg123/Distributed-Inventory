local metaExists = redis.call('EXISTS', KEYS[1])
if tonumber(metaExists) == 1 then
    redis.call('INCRBY', KEYS[2], ARGV[1])
    redis.call('INCRBY', KEYS[3], ARGV[1])
    return 1
else
    return 0
end
