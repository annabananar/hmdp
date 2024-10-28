if(redis.call('get', KEYS[1])) then
    return redis.call('delete', KEYS[1])
end
return 0