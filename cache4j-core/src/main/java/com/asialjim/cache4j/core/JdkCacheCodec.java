package com.asialjim.cache4j.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * JDK 序列化编解码器。
 *
 * <p>仅用于兼容与测试，<strong>不推荐跨服务、跨版本使用</strong>：序列化结果与类版本强耦合，
 * 类的任何结构变化都可能导致反序列化失败。生产环境请提供 JSON 等显式格式的 {@link CacheCodec}。</p>
 *
 * @param <V> 值类型
 * @author Asial Jim
 */
public final class JdkCacheCodec<V extends Serializable> implements CacheCodec<V> {

    @Override
    public byte[] encode(V value) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(256);
             ObjectOutputStream stream = new ObjectOutputStream(out)) {
            stream.writeObject(value);
            stream.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new CacheException(CacheResCode.CODEC_ENCODE_FAILED, "JDK 序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public V decode(byte[] bytes) {
        try (ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (V) stream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new CacheException(CacheResCode.CODEC_DECODE_FAILED, "JDK 反序列化失败", e);
        }
    }
}
