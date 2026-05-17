package com.xai.trident.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic trigger for {@link FaxEngineService#listenForInboundFax()}.
 *
 * <p>Gated behind the {@code dev} profile because the underlying method is
 * still a simulator — it randomly fabricates an inbound fax 20% of the time
 * and writes real rows to {@code fax_logs}, {@code contacts}, and
 * {@code fax_metadata}. Running it in production would silently accumulate
 * ~17,000 synthetic rows per day per node.
 *
 * <p>When a real inbound listener (modem driver, SIP/T.38 gateway, etc.)
 * exists, drop the {@code @Profile} and inline the call here — or just
 * delete this class and put {@code @Scheduled} on whatever the real listener
 * loop ends up being.
 *
 * <p>To enable locally:
 * <pre>{@code
 *   --spring.profiles.active=dev
 * }</pre>
 *
 * <p>The {@code Dockerfile} ships with {@code SPRING_PROFILES_ACTIVE=prod},
 * so production never picks this bean up.
 */
@Component
@Profile("dev")
public class InboundFaxSimulator {

    private static final Logger logger = LoggerFactory.getLogger(InboundFaxSimulator.class);

    private final FaxEngineService faxEngineService;

    public InboundFaxSimulator(FaxEngineService faxEngineService) {
        this.faxEngineService = faxEngineService;
        logger.warn("Inbound-fax simulator enabled (dev profile). " +
                "Synthetic fax rows will be written every 5 seconds with ~20% probability.");
    }

    @Scheduled(fixedRate = 5000)
    public void poll() {
        faxEngineService.listenForInboundFax();
    }
}
