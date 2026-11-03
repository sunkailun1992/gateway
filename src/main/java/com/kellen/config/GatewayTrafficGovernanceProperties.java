package com.kellen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 网关流量治理配置。
 *
 * <p>与后端 utils 的 {@code traffic.governance} 配置键保持一致，方便统一放到 Nacos。</p>
 */
@Component
@ConfigurationProperties(prefix = "traffic.governance")
public class GatewayTrafficGovernanceProperties {

    /**
     * 是否启用网关流量治理头标准化。
     */
    private boolean enabled = true;

    /**
     * 请求侧配置。
     */
    private Request request = new Request();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Request getRequest() {
        return request;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    /**
     * 请求侧配置。
     */
    public static class Request {

        private String releaseVersionHeader = "X-Release-Version";

        private String laneHeader = "X-Traffic-Lane";

        private String canaryTagHeader = "X-Canary-Tag";

        private String canaryWeightHeader = "X-Canary-Weight";

        private String defaultReleaseVersion = "1.0.0";

        private String defaultLane = "stable";

        private boolean tagFallbackToReleaseVersion = false;

        private boolean allowClientWeightHeader = false;

        private String allowedValuePattern = "^[A-Za-z0-9._:-]{1,64}$";

        public String getReleaseVersionHeader() {
            return releaseVersionHeader;
        }

        public void setReleaseVersionHeader(String releaseVersionHeader) {
            this.releaseVersionHeader = releaseVersionHeader;
        }

        public String getLaneHeader() {
            return laneHeader;
        }

        public void setLaneHeader(String laneHeader) {
            this.laneHeader = laneHeader;
        }

        public String getCanaryTagHeader() {
            return canaryTagHeader;
        }

        public void setCanaryTagHeader(String canaryTagHeader) {
            this.canaryTagHeader = canaryTagHeader;
        }

        public String getCanaryWeightHeader() {
            return canaryWeightHeader;
        }

        public void setCanaryWeightHeader(String canaryWeightHeader) {
            this.canaryWeightHeader = canaryWeightHeader;
        }

        public String getDefaultReleaseVersion() {
            return defaultReleaseVersion;
        }

        public void setDefaultReleaseVersion(String defaultReleaseVersion) {
            this.defaultReleaseVersion = defaultReleaseVersion;
        }

        public String getDefaultLane() {
            return defaultLane;
        }

        public void setDefaultLane(String defaultLane) {
            this.defaultLane = defaultLane;
        }

        public boolean isTagFallbackToReleaseVersion() {
            return tagFallbackToReleaseVersion;
        }

        public void setTagFallbackToReleaseVersion(boolean tagFallbackToReleaseVersion) {
            this.tagFallbackToReleaseVersion = tagFallbackToReleaseVersion;
        }

        public boolean isAllowClientWeightHeader() {
            return allowClientWeightHeader;
        }

        public void setAllowClientWeightHeader(boolean allowClientWeightHeader) {
            this.allowClientWeightHeader = allowClientWeightHeader;
        }

        public String getAllowedValuePattern() {
            return allowedValuePattern;
        }

        public void setAllowedValuePattern(String allowedValuePattern) {
            this.allowedValuePattern = allowedValuePattern;
        }
    }
}
