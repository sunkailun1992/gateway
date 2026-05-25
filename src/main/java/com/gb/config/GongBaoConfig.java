package com.gb.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 工保科技配置内容
 *
 * @author sunkailun
 * @DateTime 2021/3/17  11:04 上午
 * @email 376253703@qq.com
 * @phone 13777579028
 * @explain
 */
@Component
public class GongBaoConfig {

    /**
     * Rsa私钥
     */
    public static String privateKey;
    /**
     * Rsa公钥
     */
    public static String publicKey;

    @Bean
    @ConditionalOnMissingBean
    public HttpMessageConverters messageConverters(ObjectProvider<HttpMessageConverter<?>> converters) {
        return new HttpMessageConverters(converters.orderedStream().collect(Collectors.toList()));
    }

    @Value("${gongbao.privateKey}")
    public void setPrivateKey(String privateKey) {
        GongBaoConfig.privateKey = privateKey;
    }

    @Value("${gongbao.publicKey}")
    public void setPublicKey(String publicKey) {
        GongBaoConfig.publicKey = publicKey;
    }
}
