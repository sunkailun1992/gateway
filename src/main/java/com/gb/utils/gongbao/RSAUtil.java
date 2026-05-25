package com.gb.utils.gongbao;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.codec.Base64Decoder;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.asymmetric.KeyType;
import cn.hutool.crypto.asymmetric.RSA;
import org.springframework.util.StringUtils;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;


/**
 *
 *
 * 类职责：<br/>
 *
 * <p>Title: DataConstant.java</p>
 * <p>Description: </p>
 * <p>Copyright: Copyright (c) 2020 工保科技 </p>
 * <p>Company: 工保科技 </p>
 *
 * <p>Author:Cmexico.Li</p>
 * <p>CreateTime:2020年11月3日上午9:40:55
 * <p>$LastChangedBy$</p>
 * <p>$LastChangedRevision$ </p>
 * <p>$LastChangedDate$ </p>
 * <p>$Id$ </p>
 */
public class RSAUtil {

  /**
   * 加密
   *
   * @param orgData
   * @param privateKey
   * @return
   */
  public static String encrypt(String orgData, String privateKey) {
    RSA rsa = SecureUtil.rsa(privateKey, null);
    return new String(Base64.encode(rsa.encrypt(orgData, KeyType.PrivateKey)));
  }

  /**
   * 解密
   *
   * @param signData
   * @param publicKey
   * @return
   */
  public static String decrypt(String signData, String publicKey) {
    RSA rsa = SecureUtil.rsa(null, publicKey);
    return new String(rsa.decrypt(signData, KeyType.PublicKey));
  }

  /**
   * 验签
   *
   * @param orgData
   * @param signData
   * @param publicKey
   * @return
   */
  public static boolean check(String orgData, String signData, String publicKey) {
    RSA rsa = SecureUtil.rsa(null, publicKey);
    String data = new String(rsa.decrypt(signData, KeyType.PublicKey));
    if (StringUtils.hasText(data) && orgData.equals(data)) {
      return true;
    } else {
      return false;
    }
  }

  public static boolean checkByPublicSign(String orgData, String signData, String publicKey) {
    try {
      X509EncodedKeySpec x509EncodedKeySpec =
          new X509EncodedKeySpec(Base64Decoder.decode(publicKey));
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      PublicKey pubKey = keyFactory.generatePublic(x509EncodedKeySpec);
      // Signature signature = Signature.getInstance("MD5withRSA");//MD5withRSA
      Signature signature = Signature.getInstance("SHA256withRSA");// SHA256withRSA
      signature.initVerify(pubKey);
      signature.update(orgData.getBytes());
      // return signature.verify(new BASE64Decoder().decodeBuffer(signData));
      return signature.verify(Base64Decoder.decode(signData));
    } catch (Exception e) {
      e.printStackTrace();
    }
    return false;
  }
  public static void main(String[] args) throws Exception {
	/*String publickey="MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCAS/Yn3dXajKJo3GmeGy1czbAvVBNoUGQewgCdS3ZFjYIEvRvsVK+0TYgKgw0ku0H5Fbe3ARJIVioH9eTtMQveTTk4JlueOBHqX8ppRo796wk/AldSVVaFio1QwL3eTCu+jLv7uNv/ZAr1wwZwo1YKWGJQNFvTiKC+6iu+5ppsvwIDAQAB";
    Gson gson = new GsonBuilder().setDateFormat(DateFormatUtil.formatStr_yyyyMMddHHmmss).create();

    String ok = "{\"page\":1,\"pageSize\":10,\"transCode\":\"Q000001\"}";
	String signData1="MlJ2s1eUoC/2Gtg5hW52jW69WK5xou0dv7F9d5EO7li7OBihoNJAS4rokq4OZuucXn0teHWKslb3NA5koxv84zbsDXhyyUd3wZyHTwg80+jA0m9hycZEz70Z6J+6qfekmR054oUNnspjcGBALkhPgRvmj1R+r2zitP9qa80TCzY=";
	TradeQueryReq dto1 = gson.fromJson(ok, TradeQueryReq.class);
	System.out.println("排序前："+ dto1);
	String orgData1 = GsonUtil.toJsonSort(dto1);
	System.out.println("排序后："+ orgData1);
	boolean verify1 = RSAKeyUtil.verify(orgData1, publickey, signData1);
	System.out.println("验签结果："+verify1);


	System.out.println();
	System.out.println();
	String notok="{\"tradeNo\":\"\",\"tradeType\":\"\",\"tradeState\":\"\",\"channelCode\":\"\",\"custName\":\"\",\"page\":1,\"pageSize\":10,\"transCode\":\"Q000001\"}";
	String signData2="ExXcXeUiwhbtAyuHEQDK31D33+mi0fv7l4ZNKfQCv9fulwgM9Rntc3SWpcnktQjnOgXJYZA48465Aw4dfrthdleOcrs5I8zGrUJLinVxU1UivJXjsCAomoQu5jVbzbIawCDn8QPT1xz77Y6G24Q+xWtOP1z4NroB/XPs11OnKTA=";
	TradeQueryReq dto2 = gson.fromJson(notok, TradeQueryReq.class);
	System.out.println("排序前："+ dto2);
	String orgData2 = GsonUtil.toJsonSort(dto2);
	System.out.println("排序后："+ orgData2);
	boolean verify2 = RSAKeyUtil.verify(orgData2, publickey, signData2);
	System.out.println("验签结果："+verify2);*/




  }

}
