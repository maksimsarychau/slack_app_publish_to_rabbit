package com.solvd.qa.spring;

import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.divider;
import static com.slack.api.model.block.Blocks.input;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.plainTextInput;
import static com.slack.api.model.view.Views.view;
import static com.slack.api.model.workflow.WorkflowSteps.stepInput;
import static com.solvd.qa.util.workflow.BuilderUtil.extract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.slack.api.bolt.App;
import com.slack.api.bolt.service.OAuthStateService;
import com.slack.api.bolt.service.builtin.AmazonS3InstallationService;
import com.slack.api.bolt.service.builtin.AmazonS3OAuthStateService;
import com.slack.api.methods.request.views.ViewsUpdateRequest;
import com.slack.api.methods.response.views.ViewsUpdateResponse;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.event.AppHomeOpenedEvent;
import com.slack.api.model.view.View;
import com.slack.api.model.view.ViewState;
import com.solvd.qa.util.workflow.BuilderUtil;
import com.solvd.qa.workflow.step.builder.JenkinsWorkflow;
import com.solvd.qa.workflow.step.builder.RabbitWorkflow;
import com.solvd.qa.workflow.step.builder.STFWorkflow;
import com.solvd.qa.workflow.step.builder.ZebrunnerWorkflow;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class MyAppSpring {

	private final static String BUCKET_NAME = System.getenv("S3_BUCKET_NAME");

	@Bean
	public App initSlackApp() {
		App app = new App().asOAuthApp(true);

		AmazonS3InstallationService installationService = new AmazonS3InstallationService(BUCKET_NAME);
		installationService.setHistoricalDataEnabled(true);
		OAuthStateService stateService = new AmazonS3OAuthStateService(BUCKET_NAME);

		app.service(installationService).service(stateService);

		app.event(AppHomeOpenedEvent.class, (payload, ctx) -> {
			View appHomeView = view(view -> view.type("home").blocks(asBlocks(
					section(section -> section.text(markdownText(mt -> mt.text(
							"*This is slack app which helps to publish RabbitMQ requests for Jenkins job triggering. Or to send job run requests directly to Jenkins*")))),
					divider(), section(section -> section.text(markdownText(mt -> mt
							.text("Such triggering can be added as a step for your custom Workflow in slack")))))));

			ctx.client().viewsPublish(r -> r.userId(payload.getEvent().getUser()).view(appHomeView));

			return ctx.ack();
		});

		app.step(new JenkinsWorkflow().buildStep());
		app.step(new RabbitWorkflow().buildStep());
		app.step(new STFWorkflow().buildStep());
		app.step(new ZebrunnerWorkflow().buildStep());

		/**
		 * handling of custom parameters setting
		 */
		app.blockAction("job_params_entered", (req, ctx) -> {
			log.info("Submit job params by user " + req.getPayload().getUser().getUsername());
			com.slack.api.bolt.response.Response rs = ctx.ack();

			Map<String, Map<String, ViewState.Value>> stateValues = req.getPayload().getView().getState().getValues();
			String[] params = ((String) stepInput(
					i -> i.value(extract(stateValues, "custom_params", "job_params_entered"))).getValue()).trim()
							.split("\\s+");
			log.info("Custom job parameters: " + Arrays.toString(params));

			List<LayoutBlock> layouts = new ArrayList<LayoutBlock>();
			req.getPayload().getView().getBlocks().remove(req.getPayload().getView().getBlocks().size() - 1);
			layouts.addAll(req.getPayload().getView().getBlocks());
			Arrays.asList(params).stream().forEach(p -> {
				layouts.add(input(input -> input.blockId(p).label(plainText("Select value for parameter " + p))
						.element(plainTextInput(plainTxt -> plainTxt.actionId(p)))));
			});

			String callbackId = (BuilderUtil.blockExist(stateValues, "routing_key")) ? RabbitWorkflow.CALLBACK_ID
					: JenkinsWorkflow.CALLBACK_ID;

			View viewUpd = view(view -> view.type("workflow_step").callbackId(callbackId)
					.blocks(asBlocks(layouts.toArray(new LayoutBlock[layouts.size()]))));
			ViewsUpdateRequest viewsUpdateRequest = ViewsUpdateRequest.builder()
					.viewId(req.getPayload().getView().getId()).view(viewUpd).build();
			ViewsUpdateResponse updateResponse = ctx.client().viewsUpdate(viewsUpdateRequest);
			if (updateResponse.getError() != null) {
				log.error("Error during view updating: " + updateResponse.getError());
			}

			return rs;
		});

		app.endpoint("GET", "/slack/oauth/completion", (req, ctx) -> {
			return com.slack.api.bolt.response.Response.builder().statusCode(200).contentType("text/html")
					.body(SlackAuthSuccessController.renderCompletionPageHtml(req.getQueryString())).build();
		});

		app.endpoint("GET", "/slack/oauth/cancellation", (req, ctx) -> {
			return com.slack.api.bolt.response.Response.builder().statusCode(200).contentType("text/html")
					.body(SlackAuthCancelController.renderCancellationPageHtml(req.getQueryString())).build();
		});

		return app;
	}

}
