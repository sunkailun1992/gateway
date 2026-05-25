package com.gb.rpc.impl;

import com.alibaba.fastjson.JSON;
import com.gb.rpc.UserRpc;
import feign.hystrix.FallbackFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class UserRpcImpl implements FallbackFactory<UserRpc> {

    @Override
    public UserRpc create(Throwable throwable) {
        return new UserRpc() {
            @Override
            public Map<String, Object> authUserSystem(Map<String, Object> map, Map<String, String> headerMap) throws Exception {
                log.error("tokenIs error，请求体参数-map：{}, 请求头参数-headerMap：{}，错误消息：{}", JSON.toJSONString(map), JSON.toJSONString(headerMap), throwable.getMessage());
                throw new Exception(throwable);
            }
        };
    }
}
