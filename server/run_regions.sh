#!/usr/bin/env bash
# 해외 지역 PC 서버 3개를 한 번에 (대조·시연용): 어바인 :8792, 마컴 :8793, 구루그람 :8794
cd "$(dirname "$0")"
MEEOS_REGION=irvine MEEOS_PORT=8792 python -u -X utf8 meeos_server.py &
MEEOS_REGION=markham MEEOS_PORT=8793 python -u -X utf8 meeos_server.py &
MEEOS_REGION=gurugram MEEOS_PORT=8794 python -u -X utf8 meeos_server.py &
wait
