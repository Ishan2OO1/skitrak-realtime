#!/bin/bash
# ALB request count and error counts per minute, run in AWS CloudShell
# usage: ./alb_metrics.sh <start-utc> <end-utc>   e.g. ./alb_metrics.sh 2026-11-19T10:01:00Z 2026-11-19T10:07:00Z
LB=${LB:?set LB=app/<alb-name>/<id> from your ALB ARN}
for M in RequestCount HTTPCode_ELB_4XX_Count HTTPCode_Target_4XX_Count HTTPCode_ELB_5XX_Count HTTPCode_Target_5XX_Count; do
  echo "== $M"
  aws cloudwatch get-metric-statistics --region us-west-2 \
    --namespace AWS/ApplicationELB --metric-name $M \
    --dimensions Name=LoadBalancer,Value=$LB \
    --start-time "$1" --end-time "$2" \
    --period 60 --statistics Sum \
    --query 'sort_by(Datapoints,&Timestamp)[].[Timestamp,Sum]' --output text
done
