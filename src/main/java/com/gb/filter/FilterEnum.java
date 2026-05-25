package com.gb.filter;

import org.springframework.util.AntPathMatcher;

/**
 * @ClassName FilterEnum
 * @Description 过滤枚举
 * @Author 孙凯伦
 * @Mobile 13777579028
 * @Email 376253703@qq.com
 * @Time 2021/7/21 10:41 上午
 */
public enum FilterEnum {
    基础("/*/"),
    用户登录("/user/login"),
    授权用户体系("/user/authUserSystem"),
    免密登录("/user/freePasswordLogin"),
    用户短信注册登录("/user/quickLogin"),
    获取用户信息("/user/getUserInfo"),
    用户发送短信("/user/sendCode"),
    用户退出登录("/user/userRegister"),
    自定义接口文档("/*/doc.html"),
    图标("/*/favicon.ico"),
    swagger("/*/swagger-ui.html"),
    swaggerUi("/*/swagger-ui/**"),
    swagger参数("/*/swagger-resources/**"),
    swagger3内容("/*/v3/**"),
    页面放行("/*/webjars/**"),
    csrf("/*/csrf"),
    公共项目("/common/**"),
    保险对接项目("/insurance-docking/**"),
    省市区("/product/province/selectRedisProvince"),
    投保咨询发送手机号("/customer/potential-customer/sendSms"),
    投保咨询校验手机号("/customer/potential-customer/validationSms"),
    投保咨询查询来源("/customer/potential-customer-source/select"),
    退保图像验证码("/insurance/insurance-refund/imageVerificationCode"),
    投保咨询提交投保咨询("/customer/potential-customer/save"),
    项目类型集合("/customer/project-type/selectList"),
    智桂通统一用户信息获取("/user/tripartite/getUserInfoByToken"),
    信息收集表单获取("/customer/promote-form/selectOne"),
    经纪人认证信息查询("/customer/agent-people/getAgentUserCertification"),
    默认回调("/internal/tradePlat/defaultNotify"),
    用户支付成功通知("/internal/tradePlat/createPayOrderNotify"),
    转账回调通知("/internal/tradePlat/transFerSubmitNotify"),
    退汇回调通知("/internal/tradePlat/refundNotify"),
    云服合作预约发送手机号验证码("/customer/cooperative-appointment/sendSms"),
    云服合作预约校验手机号验证码("/customer/cooperative-appointment/validationSms"),
    平台文档设置表("/insurance/platform-document/select"),
    云服合作预约("/customer/cooperative-appointment/save"),
    渠道单条查询("/product/channel/selectOne"),
    企业渠道单条查询("/product/corporation-channel/selectOne"),
    渠道请求单条查询("/channel/channel-request/selectOne"),
    产品保障信息("/product/product-sku-guarantee/selectList"),
    产品条款信息("/product/product-spu-clause/selectList"),
    产品规格("/product/product-sku/select"),
    产品组合查询("/product/product-assembly/selectList");

    private String state;

    /**
     *
     * @auther: 孙凯伦
     * @mobile: 13777579028
     * @email: 376253703@qq.com
     * @name: isRelease
     * @description: TODO  判断参数过滤
     * @param url
     * @return: java.lang.Boolean
     * @date: 2021/7/21 10:52 上午
     *
     */
    public static Boolean isRelease(String url){
        for (FilterEnum filterEnum : FilterEnum.values()) {
            if (new AntPathMatcher().match(filterEnum.getState(), url)) {
                //过滤条件
                Boolean intercept = true;
                /**
                 * 过滤掉特殊条件
                 */
                if(new AntPathMatcher().match("/*/actuator/**", url)){
                    intercept = false;
                }
                //放行
                if(intercept) {
                    return true;
                }
            }
        }
        return false;
    }

    FilterEnum(String state) {
        this.state = state;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
