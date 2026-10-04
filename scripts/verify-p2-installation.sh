#!/usr/bin/env bash
set -euo pipefail

repository_dir="${1:?Usage: verify-p2-installation.sh <p2-repository-directory> <eclipse-repository-url>}"
platform_repository="${2:?Usage: verify-p2-installation.sh <p2-repository-directory> <eclipse-repository-url>}"
repository_dir="$(cd "$repository_dir" && pwd)"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
maven_command="${MAVEN_COMMAND:-mvn}"

if [[ "$maven_command" == *[[:space:]]* ]] || ! command -v "$maven_command" >/dev/null; then
  echo "MAVEN_COMMAND must name one executable available on PATH: $maven_command" >&2
  exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/eclipse-acp-p2.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT
destination="$work_dir/eclipse"
bundle_pool="$work_dir/bundle-pool"
profile="EclipseACPCompatibility"
director=(
  "$maven_command" --batch-mode --no-transfer-progress
  -f "$project_dir/releng/dev.eclipseacp.repository/pom.xml"
  "org.eclipse.tycho:tycho-p2-director-plugin:5.0.4:director"
  "-Ddestination=$destination"
  "-Dbundlepool=$bundle_pool"
  "-Dprofile=$profile"
)

"${director[@]}" \
  "-Drepositories=$platform_repository,file:$repository_dir" \
  "-DinstallIUs=org.eclipse.platform.ide,dev.eclipseacp.feature.feature.group"

if ! installed_roots="$("${director[@]}" -DlistInstalledRoots=true 2>&1)"; then
  echo "Unable to inspect the provisioned Eclipse profile." >&2
  printf '%s\n' "$installed_roots" >&2
  exit 1
fi
if ! grep -Eq 'dev\.eclipseacp\.feature\.feature\.group[/ ]+[0-9]+\.[0-9]+\.[0-9]+\.' <<<"$installed_roots"; then
  echo "The Eclipse ACP feature is not an installed root after provisioning." >&2
  printf '%s\n' "$installed_roots" >&2
  exit 1
fi

"${director[@]}" \
  "-Drepositories=file:$repository_dir" \
  -DuninstallIUs=dev.eclipseacp.feature.feature.group

if ! remaining_roots="$("${director[@]}" -DlistInstalledRoots=true 2>&1)"; then
  echo "Unable to inspect the Eclipse profile after uninstall." >&2
  printf '%s\n' "$remaining_roots" >&2
  exit 1
fi
if grep -q 'dev.eclipseacp.feature.feature.group' <<<"$remaining_roots"; then
  echo "The Eclipse ACP feature remains installed after uninstall." >&2
  printf '%s\n' "$remaining_roots" >&2
  exit 1
fi
if ! grep -q 'org.eclipse.platform.ide' <<<"$remaining_roots"; then
  echo "The host Eclipse Platform was damaged by uninstall." >&2
  printf '%s\n' "$remaining_roots" >&2
  exit 1
fi

echo "p2 install/uninstall verified against $platform_repository"
