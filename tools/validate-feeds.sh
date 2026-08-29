#!/usr/bin/env bash
set -euo pipefail

asset="${1:-app/src/main/assets/curated-show-layout-v128.json}"
output="${2:-build/feed-validation.tsv}"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
mkdir -p "$(dirname "$output")"
partial="$output.partial"
printf 'index\tstatus\thttp\tbytes\tcontent_type\tid\ttitle\turl\n' >"$partial"

jq -r '.shows | to_entries[] | select(.value.subscribed == true and (.value.feedUrl // "") != "") | [.key,.value.id,.value.title,.value.feedUrl] | @tsv' "$asset" |
while IFS=$'\t' read -r index id title url; do
    printf '%s\t%s\t%s\t%s\n' "$index" "$id" "$title" "$url" >"$work_dir/input-$index"
done

validate_one() {
    local input_file="$1" index id title url body meta curl_status http_code content_type size status reason
    IFS=$'\t' read -r index id title url <"$input_file"
    body="$work_dir/body-$index"
    meta="$work_dir/meta-$index"
    curl_status=0
    curl -L --compressed --range 0-524287 --connect-timeout 12 --max-time 35 --retry 1 \
        -A 'WeeklyDJShows/1.3 feed validation' -sS -o "$body" \
        -w '%{http_code}\t%{content_type}\t%{size_download}\t%{url_effective}' "$url" >"$meta" || curl_status=$?
    if [[ "$curl_status" -ne 0 ]]; then
        status="TEMPORARY_ERROR"
        reason="curl-$curl_status"
        http_code="000"
        content_type=""
        size="0"
    else
        IFS=$'\t' read -r http_code content_type size _ <"$meta"
        if [[ "$http_code" == "404" || "$http_code" == "410" ]]; then
            status="MISSING"
            reason="HTTP $http_code"
        elif [[ "$http_code" =~ ^(401|403|408|425|429|5[0-9][0-9])$ ]]; then
            status="TEMPORARY_ERROR"
            reason="HTTP $http_code"
        elif [[ ! "$http_code" =~ ^(200|206)$ ]]; then
            status="INVALID"
            reason="HTTP $http_code"
        elif ! LC_ALL=C grep -Eiq '<(rss|feed|rdf:RDF)([[:space:]>])' "$body"; then
            status="INVALID"
            reason="keine RSS/Atom-Struktur"
        elif ! LC_ALL=C grep -Eiq '<(item|entry)([[:space:]>])' "$body"; then
            status="EMPTY"
            reason="keine Folge im Feed"
        else
            status="OK"
            reason="Feed mit Folgen"
        fi
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$index" "$status" "$http_code" "$size" "$content_type" "$id" "$title" "$url" >"$work_dir/result-$index"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$index" "$status" "$http_code" "$size" "$content_type" "$id" "$title" "$url" >>"$partial"
    printf '%s\t%s\t%s\n' "$status" "$title" "$reason" >&2
}
export -f validate_one
export work_dir
export partial
find "$work_dir" -name 'input-*' -print0 | xargs -0 -n1 -P10 bash -c 'validate_one "$1"' _

{
    printf 'index\tstatus\thttp\tbytes\tcontent_type\tid\ttitle\turl\n'
    sort -t$'\t' -k1,1n "$work_dir"/result-*
} >"$output"
rm -f "$partial"

printf '\nZusammenfassung:\n'
awk -F '\t' 'NR > 1 {count[$2]++} END {for (status in count) print status, count[status]}' "$output" | sort
printf 'Bericht: %s\n' "$output"
