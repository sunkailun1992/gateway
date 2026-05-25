package com.gb.rpc;

import com.gb.rpc.impl.UserRpcImpl;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.Map;

@FeignClient(value = "user", fallbackFactory = UserRpcImpl.class)
public interface UserRpc {

    /**
     * 授权用户
     *
     * @param map: 请求体参数Map
     * @param headerMap: 请求头Map
     * @return java.util.Map
     * @author sunx
     * @DateTime 2022/11/25  13:58:00
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    @RequestMapping(value = "/authUserSystem", method = RequestMethod.POST)
    Map<String, Object> authUserSystem(@RequestBody Map<String, Object> map, @RequestHeader Map<String, String> headerMap) throws Exception;
}
