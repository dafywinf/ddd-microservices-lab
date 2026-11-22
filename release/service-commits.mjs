// semantic-release plugin: the standard commit analyser and release-notes generator, but each service only sees the
// commits that changed files in its own folder. Without this, a fix in order-service would also release
// inventory-service. (Does what semantic-release-monorepo did; that plugin is no longer maintained.)
import { execFileSync } from "node:child_process";
import { analyzeCommits as analyze } from "@semantic-release/commit-analyzer";
import { generateNotes as notes } from "@semantic-release/release-notes-generator";

const touchesPath = (hash, path, cwd) =>
  execFileSync("git", ["diff-tree", "--root", "--no-commit-id", "--name-only", "-r", hash], { cwd, encoding: "utf8" })
    .split("\n")
    .some((file) => file.startsWith(`${path}/`));

const onlyServiceCommits = ({ path }, context) => {
  const commits = context.commits.filter((commit) => touchesPath(commit.hash, path, context.cwd));
  context.logger.log(`${commits.length} of ${context.commits.length} commits since the last release touch ${path}/`);
  return { ...context, commits };
};

export const analyzeCommits = (pluginConfig, context) => analyze(pluginConfig, onlyServiceCommits(pluginConfig, context));

export const generateNotes = (pluginConfig, context) => notes(pluginConfig, onlyServiceCommits(pluginConfig, context));
