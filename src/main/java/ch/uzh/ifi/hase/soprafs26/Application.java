package ch.uzh.ifi.hase.soprafs26;

import ch.uzh.ifi.hase.soprafs26.config.settings.ServerSettingsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@RestController
@SpringBootApplication
@EnableScheduling
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@GetMapping(value = "/", produces = MediaType.TEXT_PLAIN_VALUE)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public String helloWorld() {
		return "The application is running.";
	}

	@Bean(destroyMethod = "shutdown")
	public ScheduledExecutorService gameScheduler(ObjectProvider<ServerSettingsProperties> serverSettingsProvider) {
		ServerSettingsProperties defaults = new ServerSettingsProperties();
		ServerSettingsProperties serverSettings = serverSettingsProvider.getIfAvailable(() -> defaults);
		ScheduledThreadPoolExecutor scheduler = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(
				serverSettings.getGameSchedulerThreadPoolSize());
		// Immediate queue removal for canceled timers avoids delayed-task buildup under reconnect churn.
		scheduler.setRemoveOnCancelPolicy(true);
		scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
		return scheduler;
	}

	@Bean
	public WebMvcConfigurer corsConfigurer(ObjectProvider<ServerSettingsProperties> serverSettingsProvider) {
		ServerSettingsProperties defaults = new ServerSettingsProperties();
		ServerSettingsProperties serverSettings = serverSettingsProvider.getIfAvailable(() -> defaults);
    	return new WebMvcConfigurer() {
      		@Override
        	public void addCorsMappings(CorsRegistry registry) {
            	registry.addMapping("/**")
                    .allowedOrigins(serverSettings.getCorsAllowedOrigins().toArray(String[]::new))
                    .allowedMethods("*")
                    .allowCredentials(true);
        	}
   	 	};
	}
}
