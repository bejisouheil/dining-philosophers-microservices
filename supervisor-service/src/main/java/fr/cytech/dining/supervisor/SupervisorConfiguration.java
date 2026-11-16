package fr.cytech.dining.supervisor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import fr.cytech.dining.common.HttpSupport;
import fr.cytech.dining.common.LamportClock;
import fr.cytech.dining.supervisor.forks.LockedPairForkTable;
import fr.cytech.dining.supervisor.forks.NaiveForkTable;
import fr.cytech.dining.supervisor.forks.RaceWindow;
import fr.cytech.dining.supervisor.forks.UnsafePairForkTable;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableConfigurationProperties(SupervisorProperties.class)
public class SupervisorConfiguration implements WebMvcConfigurer {

    private final LamportClock clock = new LamportClock();

    @Bean
    LamportClock supervisorClock() {
        return clock;
    }

    @Bean
    NaiveForkTable naiveForkTable(SupervisorProperties properties) {
        return new NaiveForkTable(properties.forks(), RaceWindow.ofMillis(properties.raceWindowMs()));
    }

    @Bean
    UnsafePairForkTable unsafePairForkTable(SupervisorProperties properties) {
        return new UnsafePairForkTable(properties.forks(), RaceWindow.ofMillis(properties.raceWindowMs()));
    }

    @Bean
    LockedPairForkTable lockedPairForkTable(SupervisorProperties properties) {
        return new LockedPairForkTable(properties.forks(), RaceWindow.ofMillis(properties.raceWindowMs()));
    }

    /**
     * Le superviseur participe aux horloges de Lamport : il recale son horloge sur celle du
     * philosophe qui l'appelle et renvoie la sienne. Ainsi « P1 rend la fourchette » puis « P2
     * l'obtient » se retrouvent datés dans le bon ordre, même si P1 et P2 ne se parlent jamais.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String header = request.getHeader(HttpSupport.LAMPORT_HEADER);
                long time = header == null ? clock.tick() : clock.update(parse(header));
                response.setHeader(HttpSupport.LAMPORT_HEADER, Long.toString(time));
                return true;
            }
        });
    }

    private static long parse(String header) {
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
