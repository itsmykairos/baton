package dev.baton.it;

import dev.baton.api.Pipeline;
import dev.baton.api.PipelineProvider;
import org.junit.jupiter.api.Test;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** How applications find and see baton-core: ServiceLoader only, no exported packages. */
class ModularityIT {

    @Test
    void builderIsFoundThroughServiceLoaderOnTheClasspath() {
        assertThat(Pipeline.builder()).isNotNull();
        assertThat(ServiceLoader.load(PipelineProvider.class).findFirst()).isPresent();
    }

    @Test
    void onTheModulePathCoreIsBoundAsAServiceAndExportsNothing() throws Exception {
        Path api = codeSource(PipelineProvider.class);
        Path core = codeSource(Class.forName("dev.baton.core.DefaultPipelineProvider"));

        // Resolve only dev.baton.api; service binding must pull in dev.baton.core.
        Configuration configuration = ModuleLayer.boot().configuration()
                .resolveAndBind(ModuleFinder.of(api, core), ModuleFinder.of(), Set.of("dev.baton.api"));
        ModuleLayer layer = ModuleLayer.boot()
                .defineModulesWithOneLoader(configuration, ClassLoader.getPlatformClassLoader());

        Module coreModule = layer.findModule("dev.baton.core").orElseThrow();
        ModuleDescriptor descriptor = coreModule.getDescriptor();
        assertThat(descriptor.exports()).isEmpty();
        assertThat(descriptor.provides()).extracting(ModuleDescriptor.Provides::service)
                .containsExactly("dev.baton.api.PipelineProvider");
        assertThat(coreModule.isExported("dev.baton.core.execution")).isFalse();

        Module apiModule = layer.findModule("dev.baton.api").orElseThrow();
        assertThat(apiModule.getDescriptor().exports())
                .extracting(ModuleDescriptor.Exports::source)
                .containsExactlyInAnyOrder("dev.baton.api", "dev.baton.api.event");

        Class<?> providerType = apiModule.getClassLoader().loadClass("dev.baton.api.PipelineProvider");
        assertThat(ServiceLoader.load(layer, providerType).findFirst()).isPresent();
    }

    private static Path codeSource(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
