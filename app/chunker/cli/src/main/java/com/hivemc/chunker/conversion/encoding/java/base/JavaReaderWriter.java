package com.hivemc.chunker.conversion.encoding.java.base;

import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.base.Converter;
import com.hivemc.chunker.conversion.encoding.base.LevelReaderWriter;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.java.base.reader.pretransform.legacy.JavaLegacyReaderPreTransformManager;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaLevelDirectoryResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolversBuilder;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.biome.JavaBiomeIDResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.biome.JavaNamedBiomeResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.blockentity.legacy.JavaLegacyBlockEntityResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.entity.legacy.JavaLegacyEntityResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.entity.legacy.JavaLegacyEntityTypeResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.entity.legacy.JavaLegacyPaintingMotiveResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.JavaNBTBlockIdentifierResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.legacy.JavaLegacyBlockIDResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.legacy.JavaLegacyBlockIdentifierResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.legacy.JavaLegacyItemIdentifierResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.itemstack.*;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.itemstack.legacy.JavaLegacyItemStackResolver;
import com.hivemc.chunker.conversion.encoding.java.base.writer.pretransform.legacy.JavaLegacyWriterPreTransformManager;

import java.io.File;

/**
 * A Java Level Reader / Writer, used to share resolvers.
 */
public interface JavaReaderWriter extends LevelReaderWriter {
    @Override
    default EncodingType getEncodingType() {
        return EncodingType.JAVA;
    }

    /**
     * Get the directory that is being used for reading/writing the level.
     *
     * @return the directory path, used as the base directory for fetching worlds.
     */
    File getLevelDirectory();

    /**
     * Build the resolvers which should be used for this version.
     *
     * @param converter the instance of the converter.
     * @return a builder so that this method can be overridden to update the resolvers to use.
     */
    default JavaResolversBuilder buildResolvers(Converter converter) {
        Version version = getVersion();
        return new JavaResolversBuilder(converter, version, true)
                .blockIDResolver(new JavaLegacyBlockIDResolver(version))
                .nbtBlockIdentifierResolver(new JavaNBTBlockIdentifierResolver(version))
                .itemIdentifierResolver(new JavaLegacyItemIdentifierResolver(converter, version, isReader()))
                .blockIdentifierResolver(new JavaLegacyBlockIdentifierResolver(converter, version, isReader(), converter.shouldAllowCustomIdentifiers()))
                .entityTypeResolver(new JavaLegacyEntityTypeResolver(version))
                .biomeNameResolver(new JavaNamedBiomeResolver(version, converter.shouldAllowCustomIdentifiers()))
                .biomeIDResolver(new JavaBiomeIDResolver(version, usesCustomBiomeSlots(converter)))
                .effectResolver(new JavaEffectResolver(version))
                .effectIDResolver(new JavaEffectIDResolver(version))
                .enchantmentResolver(new JavaEnchantmentResolver(version))
                .enchantmentIDResolver(new JavaEnchantmentIDResolver(version))
                .hornInstrumentResolver(new JavaHornInstrumentResolver(version))
                .paintingMotiveResolver(new JavaLegacyPaintingMotiveResolver(version))
                .potionTypeResolver(new JavaPotionTypeResolver(version))
                .mapColorsResolver(new JavaMapColorsResolver(version))
                .mapDecorationResolver(new JavaMapDecorationResolver(version))
                .mapDecorationIDResolver(new JavaMapDecorationIDResolver(version))
                .trimPatternResolver(new JavaTrimPatternResolver(version))
                .trimMaterialResolver(new JavaTrimMaterialResolver(version))
                .bannerPatternShortNameResolver(new JavaBannerPatternShortNameResolver(version))
                .bannerPatternResolver(new JavaBannerPatternResolver(version))
                .itemStackResolverConstructor(JavaLegacyItemStackResolver::new)
                .blockEntityResolverConstructor((resolvers) -> new JavaLegacyBlockEntityResolver(version, resolvers))
                .entityResolverConstructor((resolvers) -> new JavaLegacyEntityResolver(version, resolvers))
                .levelDirectoryResolver(new JavaLevelDirectoryResolver(getLevelDirectory()))
                .preTransformManager(isReader() ? new JavaLegacyReaderPreTransformManager(version) : new JavaLegacyWriterPreTransformManager(version));
    }

    /**
     * 是否启用自定义群系槽位（1.13+ 群系写到 1.12.2 用不到的编号上）。
     *
     * <p>只在无损（幽灵）模式启用：那些编号要靠服务端的 GhostBlocks 插件才能翻译回真实群系，
     * 与幽灵方块属于同一个前提。降级模式的产物要保持原版 1.12.2 就能正确打开。</p>
     *
     * <p>读侧同样启用：本方法被读写两侧共用，只有两边都登记才能让产物循环转换往返无损。
     * 对原生 1.12.2 存档无影响——那些编号原版本就不会产生（40-50 是 1.13+ 才有的，79-96 更晚）。</p>
     *
     * @param converter the converter instance.
     * @return true if the custom biome slots should be used.
     */
    static boolean usesCustomBiomeSlots(Converter converter) {
        return converter instanceof WorldConverter
                && ((WorldConverter) converter).getLosslessBlocks() != null;
    }
}
