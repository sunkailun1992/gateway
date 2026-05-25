package com.gb.utils;


import okhttp3.*;
import org.springframework.util.Assert;

import javax.net.ssl.*;
import java.io.IOException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Map.Entry;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * okhttp工具类
 *
 * @author sunkailun
 * @DateTime 2018/5/21  上午11:07
 * @email 376253703@qq.com
 * @phone 13777579028
 */
public final class OkhttpUtils {
    /**
     * 安卓手机请求头
     */
    private static final String MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 5.1.1; Nexus 6 Build/LYZ28E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/48.0.2564.23 Mobile Safari/537.36";

    /**
     * mac电脑请求头
     */
    private static final String PC_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/54.0.2840.98 Safari/537.36";

    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");


    private static OkHttpClient client = null;


    /**
     * 创建请求参数
     *
     * @param params:
     * @return okhttp3.RequestBody
     * @author sunkailun
     * @DateTime 2018/5/21  上午11:09
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    public static RequestBody createRequestParamsFrom(Map<String, Object> params) {
        FormBody.Builder builder = new FormBody.Builder();
        if (null != params && !params.isEmpty()) {
            for (Entry<String, Object> entry : params.entrySet()) {
                if (null != entry.getValue()) {
                    builder.add(entry.getKey(), entry.getValue().toString());
                }
            }
        }
        return builder.build();
    }


    /**
     * 创建请求参数
     *
     * @param params:
     * @return okhttp3.RequestBody
     * @author sunkailun
     * @DateTime 2018/5/21  上午11:09
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    public static RequestBody createRequestParamsXml(String params) {
        return RequestBody.create(MediaType.parse("application/xml"), params);
    }

    /**
     * 创建请求参数
     *
     * @param json:
     * @return okhttp3.RequestBody
     * @author sunkailun
     * @DateTime 2018/5/21  上午11:09
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    public static RequestBody createRequestParamsJson(String json) {
        return RequestBody.create(JSON, json);
    }

    /**
     * 获得okhttp类
     *
     * @param :
     * @return okhttp3.OkHttpClient
     * @author sunkailun
     * @DateTime 2018/5/21  上午11:10
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    public static OkHttpClient getOkHttpClient() {
        if (null == client) {
            try {
                TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(
                            X509Certificate[] chain,
                            String authType) throws CertificateException {
                    }

                    @Override
                    public void checkServerTrusted(
                            X509Certificate[] chain,
                            String authType) throws CertificateException {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }};
                SSLContext sslContext = SSLContext.getInstance("SSL");
                sslContext.init(null, trustAllCerts, new SecureRandom());
                SSLSocketFactory sslSocketFactory = sslContext
                        .getSocketFactory();
                client = new OkHttpClient.Builder().connectTimeout(10, SECONDS).readTimeout(60, SECONDS)
                        .sslSocketFactory(sslSocketFactory, new X509TrustManager() {
                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType)
                                    throws CertificateException {
                            }

                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType)
                                    throws CertificateException {
                            }
                        })
                        .hostnameVerifier(new HostnameVerifier() {
                            @Override
                            public boolean verify(String hostname, SSLSession session) {
                                return true;
                            }
                        })
                        .build();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return client;
    }

    /**
     * 发送请求
     *
     * @param url:
     * @param method:
     * @param body:
     * @return okhttp3.Request
     * @author sunkailun
     * @DateTime 2018/5/21  上午11:18
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    public static Request request(String url, String method, RequestBody body) {
        Assert.notNull(url, "url为空");
        Assert.notNull(method, "请求方式为空");
        return new Request.Builder().addHeader("User-Agent", PC_USER_AGENT).addHeader("Gongbao-Origin", "GONG_BAO_NET").url(url).method(method, body).build();
    }


    /**
     * 发送请求
     *
     * @param url:
     * @param method:
     * @param body:
     * @return okhttp3.Request
     * @author sunkailun
     * @DateTime 2018/5/21  上午11:18
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    public static Request request(String url, String method, RequestBody body,String token) {
        Assert.notNull(url, "url为空");
        Assert.notNull(method, "请求方式为空");
        return new Request.Builder().addHeader("User-Agent", PC_USER_AGENT).addHeader("Gongbao-Origin", "GONG_BAO_NET").addHeader("Authorization", token).url(url).method(method, body).build();
    }


    /**
     * 响应内容
     *
     * @param request:
     * @return okhttp3.Response
     * @author sunkailun
     * @DateTime 2018/5/21  上午11:20
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    public static Response execute(Request request) throws IOException {
        return getOkHttpClient().newCall(request).execute();
    }

    /**
     * 发送post请求
     *
     * @param url    地址
     * @param params 参数
     * @param type   类型（0：fron，1：json）
     * @return
     * @throws IOException
     */
    public static String post(String url, Map<String, Object> params,Integer type) throws Exception {
        RequestBody body = null;
        if(type == 0){
            body = createRequestParamsFrom(params);
        }else if(type == 1){
            body = createRequestParamsJson(JsonUtil.json(params));
        }
        Request request = request(url, "POST", body);
        Response response = execute(request);
        return response.body().string();
    }


    /**
     * 发送post请求
     *
     * @param url    地址
     * @param params 参数
     * @param type   类型（0：fron，1：json）
     * @return
     * @throws IOException
     */
    public static String post(String url, Map<String, Object> params,Integer type, String token) throws Exception {
        RequestBody body = null;
        if(type == 0){
            body = createRequestParamsFrom(params);
        }else if(type == 1){
            body = createRequestParamsJson(JsonUtil.json(params));
        }
        Request request = request(url, "POST", body,token);
        Response response = execute(request);
        return response.body().string();
    }


    /**
     * 发送get请求
     *
     * @param url: 地址
     * @return java.lang.String
     * @author sunkailun
     * @DateTime 2018/5/21  上午11:23
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    public static String get(String url,Integer type, String token) throws IOException {
        Request request = request(url, "GET", null,token);
        Response response = execute(request);
        return response.body().string();
    }

    /**
     * 发送get请求
     *
     * @param url: 地址
     * @return java.lang.String
     * @author sunkailun
     * @DateTime 2018/5/21  上午11:23
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    public static String get(String url) throws IOException {
        Request request = request(url, "GET", null);
        Response response = execute(request);
        return response.body().string();
    }


    /**
     * 发送get请求返回字节
     *
     * @param url:  地址
     * @param isPc: 是否PC端口
     * @return java.lang.String
     * @author sunkailun
     * @DateTime 2018/5/21  上午11:23
     * @email 376253703@qq.com
     * @phone 13777579028
     */
    public static byte[] getByte(String url, boolean isPc) throws IOException {
        Request request = request(url, "GET", null);
        Response response = execute(request);
        return response.body().bytes();
    }

}
