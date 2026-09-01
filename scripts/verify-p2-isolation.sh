#!/usr/bin/env bash
set -euo pipefail

repository_dir="${1:?Usage: verify-p2-isolation.sh <p2-repository-directory>}"
content_jar="$repository_dir/content.jar"

if [[ ! -f "$content_jar" ]]; then
  echo "Missing p2 metadata: $content_jar" >&2
  exit 1
fi

mapfile -t unit_ids < <(
  unzip -p "$content_jar" content.xml |
    grep -o "unit id='[^']*'" |
    sed -e "s/^unit id='//" -e "s/'$//"
)

if [[ ${#unit_ids[@]} -ne 5 ]]; then
  echo "Unexpected number of p2 installable units: ${#unit_ids[@]}" >&2
  printf '  %s\n' "${unit_ids[@]}" >&2
  exit 1
fi

for unit_id in "${unit_ids[@]}"; do
  case "$unit_id" in
    dev.eclipseacp.client|dev.eclipseacp.feature.feature.group|dev.eclipseacp.feature.feature.jar|a.jre.javase)
      ;;
    *.dev.eclipseacp.category)
      ;;
    *)
      echo "Refusing to publish a foreign p2 unit: $unit_id" >&2
      exit 1
      ;;
  esac
done

metadata="$(unzip -p "$content_jar" content.xml)"
if grep -Eq "namespace='org\.eclipse\.equinox\.p2\.iu' name='(org\.eclipse\.|com\.google\.gson)'" <<<"$metadata"; then
  echo "The feature declares external p2 requirements; publish only the ACP feature and bundle." >&2
  exit 1
fi

echo "p2 repository isolation verified: only Eclipse ACP units are published."
