// semantic-release config, shared by the services: run with SERVICE=<folder>, e.g. SERVICE=order-service.
// Each service gets its own version line and tags (order-service-v1.3.1). The git tag is the only record of the
// version: nothing is committed back to main.
const service = process.env.SERVICE;
if (!service) {
  throw new Error("Set SERVICE to the service folder, e.g. SERVICE=order-service");
}

const conventional = { preset: "conventionalcommits", path: service };

export default {
  branches: ["main"],
  tagFormat: `${service}-v\${version}`,
  plugins: [
    ["./release/service-commits.mjs", conventional],
    // No comments or labels on every PR in the release: the first release would otherwise comment on all of them
    ["@semantic-release/github", { successComment: false, releasedLabels: false }],
    // Hands the version to later CI steps (the image build)
    ["@semantic-release/exec", { successCmd: 'echo "version=${nextRelease.version}" >> "${GITHUB_OUTPUT:-/dev/null}"' }],
  ],
};
