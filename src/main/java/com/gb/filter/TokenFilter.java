package com.gb.filter;

import cn.hutool.core.map.MapUtil;
import com.alibaba.fastjson.JSON;
import com.gb.rpc.UserRpc;
import com.gb.utils.JsonUtil;
import com.gb.utils.RedisUtils;
import com.github.xiaoymin.knife4j.core.util.CollectionUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;


/**
 * Ordered 负责filter的顺序，数字越小越优先，越靠前。
 * <p>
 * GatewayFilter：
 * 需要通过spring.cloud.routes.filters 配置在具体路由下，
 * 只作用在当前路由上或通过spring.cloud.default-filters配置在全局，作用在所有路由上。
 * 需要用代码的形式，配置一个RouteLocator，里面写路由的配置信息。
 * <p>
 * GlobalFilter：
 * 全局过滤器，不需要在配置文件中配置，作用在所有的路由上，最终通过GatewayFilterAdapter包装成GatewayFilterChain可识别的过滤器，
 * 它为请求业务以及路由的URI转换为真实业务服务的请求地址的核心过滤器，不需要配置，系统初始化时加载，并作用在每个路由上。
 * 代码配置需要声明一个GlobalFilter对象。
 * <p>
 * <p>
 * 对一个应用来说，GatewayFilter和GlobalFilter是等价的，order也会按照顺序进行拦截。所以两个order不要写一样！
 *
 * @author lsy
 */
@Slf4j
public class TokenFilter implements GlobalFilter, Ordered {

    /**
     * 设置token剩余多少时间，重新附加时间
     */
    public static final int EXPIRATION_TIME = 20;
    /**
     * redis缓存
     */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserRpc userRpc;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        //请求地址
        String reqPath = exchange.getRequest().getURI().getPath();
        //判断是swagger文档全部放行
        if (FilterEnum.isRelease(reqPath)) {
            return chain.filter(exchange);
        }
        HttpHeaders headerNames = exchange.getRequest().getHeaders();
        String token = getToken(headerNames.get("token"));
        if (StringUtils.isBlank(token)) {
            return unAuth(exchange);
        } else {
            /**
             * 放行特殊token
             */
            if ("eaa1929451cd43efb3f4668eed25e3f9".equals(token)) {
                return chain.filter(exchange);
            }
            //用户集合
            Map<String, Object> user = RedisUtils.getToken(stringRedisTemplate, token);
            if(MapUtil.isEmpty(user)) {
                //判断token在工保通是否有效，有效的话，授权工保网缓存30分钟，在工保网存在的话，覆盖原来的缓存信息，也是30分钟
                if(!tokenIs(token)) {
                    log.error("缓存token-{}失效，{}地址未找到接口权限", token, reqPath);
                    return unAuth(exchange);
                }
                user = RedisUtils.getToken(stringRedisTemplate, token);
            }
            //取出api可访问地址
            Map<String, Object> apiMap = getApi(user.get("appCode").toString(), user.get("userName").toString(), token);
            List<Map<String, Object>> api = (List<Map<String, Object>>)apiMap.get("api");
            if (CollectionUtils.isEmpty(api)) {
                return unAuth(exchange);
            }
            log.debug("请求地址：" + reqPath);
            log.debug("token：" + token);
            log.debug("可使用权限集合：" + JsonUtil.json(api));
            //判断时间，然后续token时间
            renewalTime((String)apiMap.get("key"), token, user, api);
            for (Map<String, Object> resource : api) {
                //通过spring的通配符匹配
                if (new AntPathMatcher().match(resource.get("value").toString(), reqPath)) {
                    log.debug(reqPath + "地址放行");
                    return chain.filter(exchange);
                }
            }
            log.error(reqPath + "地址未找到接口权限");
            return unAuth(exchange);
        }
    }

    /**
     * 判断token是否有效
     *
     * @param token:
     * @return void
     * @author sunkailun
     * @DateTime 2021/3/19  3:38 下午
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    private Boolean tokenIs(String token) {
        boolean tokenIs = false;
        try {
            //请求头
            Map<String, String> headerMap = Maps.newHashMap();
            headerMap.put("contentType", "application/json;charset=UTF-8");
            headerMap.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/54.0.2840.98 Safari/537.36");
            headerMap.put("Gongbao-Origin", "GONG_BAO_NET");
            headerMap.put("sourceCode","GONG_BAO_NET");
            headerMap.put("sourceValueCode","SILENT_REGISTER");
            headerMap.put("appCode","net-user");
            headerMap.put("businessDetails","gateWay-define");
            //请求体
            Map<String, Object> map = Maps.newHashMap();
            map.put("token", token);
            Map<String, Object> resultMap = userRpc.authUserSystem(map, headerMap);
            log.debug("判断token是否有效方法，token：{}, 响应结果：{}", token, JSON.toJSONString(resultMap));
            if(Objects.nonNull(resultMap) && Objects.nonNull(resultMap.get("code")) && StringUtils.equals(String.valueOf(resultMap.get("code")), "0")) {
                   tokenIs = true;
            }
        } catch (Exception e) {
            log.error("判断token是否有效方法，token：{}, 异常信息：", token, e);
            log.error("tokenIs error", e);
        }
        return tokenIs;
    }

    private Mono unAuth(ServerWebExchange exchange) {
        //不允许访问，禁止访问
        ServerHttpResponse response = exchange.getResponse();
        //这个状态码是401
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    /**
     * 续时token
     *
     * @param token:
     * @param user:
     * @param api:
     * @return void
     * @author sunkailun
     * @DateTime 2021/1/7  下午5:35
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    private void renewalTime(String key, String token, Map<String, Object> user, List<Map<String, Object>> api) {
        log.debug("续时token--key：{}，token：{}，user：{}，api：{}", key, token, JsonUtil.json(user), JsonUtil.json(api));
        //根据key获取过期时间并换算成指定单位
        Long expire = stringRedisTemplate.getExpire(token, TimeUnit.MINUTES);
        if (expire <= EXPIRATION_TIME) {
            //通过token(通过加盐后的key才能获得),获得user对象
            RedisUtils.add(stringRedisTemplate, token, user, 30, TimeUnit.MINUTES);
            //获得ip
            String ip = String.valueOf(RedisUtils.getMap(stringRedisTemplate, key, "ip"));
            //键值对缓存,通过账号获得token
            Map<String, Object> map = Maps.newHashMap();
            map.put("user", user);
            map.put("token", token);
            map.put("resource", String.valueOf(RedisUtils.getMap(stringRedisTemplate, key, "resource")));
            map.put("api", api);
            map.put("ip", ip);
            //通过账号获得,map缓存对象,包含token,user
            RedisUtils.addMap(stringRedisTemplate, key, map, 30, TimeUnit.MINUTES);
        }
    }

    /**
     * 取出api可访问地址
     * @param appCode：应用码值
     * @param userName：账户名
     * @param token：鉴权令牌
     * @return List<Map<String, Object>>
     */
    private Map<String, Object> getApi(String appCode, String userName, String token) {
        Map<String, Object> resultMap = Maps.newHashMap();
        String key = appCode + "_" + userName;
        String redisToken = String.valueOf(RedisUtils.getMap(stringRedisTemplate, key, "token"));
        List<Map<String, Object>> api = Lists.newArrayList();
        if(StringUtils.equals(token, redisToken)) {
            api = JsonUtil.java(String.valueOf(RedisUtils.getMap(stringRedisTemplate, key, "api")), List.class);
            resultMap.put("key", key);
            resultMap.put("api", api);
            return resultMap;
        }
        String[] sourceCodeArrays = {"APP", "H5", "OFFICIAL_ACCOUNT"};
        for(String sourceCode : sourceCodeArrays) {
            key = appCode + "_" + userName + "_" + sourceCode;
            redisToken = String.valueOf(RedisUtils.getMap(stringRedisTemplate, key, "token"));
            if(StringUtils.equals(token, redisToken)) {
                api = JsonUtil.java(String.valueOf(RedisUtils.getMap(stringRedisTemplate, key, "api")), List.class);
                resultMap.put("key", key);
                resultMap.put("api", api);
                break;
            }
        }
        return resultMap;
    }


    /**
     * 获取鉴权令牌
     * @param o 头信息
     * @return String
     */
    private String getToken(Object o) {
        String token = StringUtils.EMPTY;
        //获取头部
        try {
            //获取头部参数
            if(Objects.nonNull(o)) {
                List<String> list = (List<String>) o;
                if(CollectionUtils.isNotEmpty(list)) {
                    token = list.get(0);
                }
            }
        } catch (Exception e) {
            log.error("获取头部参数--token解析异常：", e);
        }
        return token;
    }

    @Override
    public int getOrder() {
        return 0;
    }

}
