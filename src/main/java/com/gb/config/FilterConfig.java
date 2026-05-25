package com.gb.config;

import com.gb.filter.TokenFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * 权限验证
 * @author sunkailun
 * @DateTime 2020/11/19  8:54 下午
 * @email 376253703@qq.com
 * @phone 13777579028
 * @explain
 */
@Configuration
public class FilterConfig {

    @Bean
    public TokenFilter tokenFilter() {
        return new TokenFilter();
    }

}
