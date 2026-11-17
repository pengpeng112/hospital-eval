#!/usr/bin/env bash
set -u
echo "== OS =="; cat /etc/os-release | head -3; uname -r
echo "== CPU/MEM =="; nproc; free -h
echo "== DISK =="; df -h /opt /var/lib/docker 2>/dev/null || df -h
echo "== DOCKER =="; docker version --format '{{.Server.Version}}' 2>&1; docker compose version 2>&1
echo "== CONTAINERS =="; docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
echo "== PORTS =="; ss -tulpn 2>/dev/null | awk 'NR==1 || /:(18080|48080|15432|16379|18012)\b/'
echo "== ALL LISTEN =="; ss -tln 2>/dev/null | awk '{print $4}' | sort -u | tail -50
echo "== NET TEST =="; for t in 10.10.8.216:1521 10.10.10.15:1521 registry-1.docker.io:443; do h=${t%:*}; p=${t#*:}; (timeout 5 bash -c "</dev/tcp/$h/$p" && echo "$t OK") 2>/dev/null || echo "$t FAIL"; done
