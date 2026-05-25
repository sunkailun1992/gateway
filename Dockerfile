FROM registry.cn-hangzhou.aliyuncs.com/gongbaowang/jre-env:8u241
COPY gateway-1.0.0.jar /app/
WORKDIR /app/
ENTRYPOINT ["/app/entrypoint.sh"]
