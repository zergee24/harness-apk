import { Command } from "commander";
import { parseCmdline } from "@deepseek-ai/dsh-cmdline";

/** @module dsh-appserver/startup — command-line provider for the appserver profile. */

/** Stable Cordis plugin name. */
export const name = "appserver-startup";

/** Services required before the listen transport can be resolved. */
export const inject = ["cmdlineArgs"];

/** Service provided by this plugin and injected by the appserver runner. */
export const APPSERVER_STARTUP_SERVICE = "appserverStartup";

/** This app's command: the transport flag and its help text. */
function appserverCommand() {
	return new Command()
		.name("dsh --profile appserver")
		.description("Serve a codex app-server compatible JSON-RPC surface over stdio.")
		.helpOption("-h, --help", "show this help")
		.option("--listen <transport>", "stdio:// (the only transport today)")
		.addHelpText("after", `
Examples:
  dsh --profile appserver --listen stdio://    serve JSON-RPC lines on stdin/stdout
`);
}

/**
 * Parse and provide the listen transport as an ordinary Cordis service. The
 * command's action publishes the transport; an unsupported transport is a
 * usage error.
 * @param ctx - plugin context carrying the command line.
 */
export function apply(ctx) {
	const program = appserverCommand();
	program.action(() => {
		const listen = program.opts().listen ?? "stdio://";
		if (listen !== "stdio://") program.error(`error: unsupported transport ${listen}`);
		ctx.provide(APPSERVER_STARTUP_SERVICE, { listen });
	});
	parseCmdline(ctx, program);
}
