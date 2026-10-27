{{- define "windx.fullname" -}}
{{- default .Release.Name .Values.fullnameOverride | replace "+" "_" | trunc 35 | trimSuffix "-" -}}
{{- end -}}

{{- define "windx.secretName" -}}
{{- default (printf "%s-secrets" (include "windx.fullname" .)) .Values.secrets.name -}}
{{- end -}}

{{- define "windx.postgresqlHost" -}}
{{- if .Values.postgresql.enabled -}}
{{- default (printf "%s-postgresql" (include "windx.fullname" .)) .Values.postgresql.serviceName -}}
{{- else -}}
{{- required "postgresql.host is required when postgresql.enabled=false" .Values.postgresql.host -}}
{{- end -}}
{{- end -}}

{{- define "windx.identityhubHost" -}}
{{- default (printf "%s-identityhub" (include "windx.fullname" .)) .Values.identityhub.serviceName -}}
{{- end -}}

{{- define "windx.connectorName" -}}
{{- default (include "windx.fullname" .) .Values.connector.fullnameOverride | trunc 50 | trimSuffix "-" -}}
{{- end -}}

{{- define "windx.vaultHost" -}}
{{- default (printf "%s-vault" (include "windx.fullname" .)) .Values.vault.serviceName -}}
{{- end -}}

{{- define "windx.vaultUrl" -}}
{{- if .Values.vault.enabled -}}
{{- printf "http://%s:8200" (include "windx.vaultHost" .) -}}
{{- else -}}
{{- required "vault.url is required when vault.enabled=false" .Values.vault.url -}}
{{- end -}}
{{- end -}}

{{- define "windx.imagePullSecrets" -}}
{{- if or .Values.imagePullSecrets .Values.connector.imagePullSecret }}
imagePullSecrets:
  {{- with .Values.imagePullSecrets }}
  {{- toYaml . | nindent 2 }}
  {{- end }}
  {{- with .Values.connector.imagePullSecret }}
  - name: {{ . | quote }}
  {{- end }}
{{- end }}
{{- end -}}

{{- define "windx.connectorSecretVolume" -}}
{{ include "windx.identityhubSecretVolume" . }}
      - key: {{ .Values.secrets.keys.managementKey | quote }}
        path: mgmt_key
{{- end -}}

{{- define "windx.labels" -}}
app.kubernetes.io/name: windx-edc-participant
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "windx.postgresqlSecretVolume" -}}
- name: participant-secrets
  secret:
    secretName: {{ (include "windx.secretName" .) | quote }}
    items:
      - key: {{ .Values.secrets.keys.postgresPassword | quote }}
        path: postgres_password
{{- end -}}

{{- define "windx.identityhubSecretVolume" -}}
- name: participant-secrets
  secret:
    secretName: {{ (include "windx.secretName" .) | quote }}
    items:
      - key: {{ .Values.secrets.keys.postgresPassword | quote }}
        path: postgres_password
      - key: {{ .Values.secrets.keys.vaultToken | quote }}
        path: vault_token
      - key: {{ .Values.secrets.keys.superuserKey | quote }}
        path: superuser_key
{{- end -}}

{{- define "windx.vaultBootstrapSecretVolume" -}}
- name: participant-secrets
  secret:
    secretName: {{ (include "windx.secretName" .) | quote }}
    items:
      - key: {{ .Values.secrets.keys.vaultToken | quote }}
        path: vault_token
      - key: {{ .Values.secrets.keys.superuserKey | quote }}
        path: superuser_key
{{- end -}}

{{- define "windx.provisionSecretVolume" -}}
- name: participant-secrets
  secret:
    secretName: {{ (include "windx.secretName" .) | quote }}
    items:
      - key: {{ .Values.secrets.keys.superuserKey | quote }}
        path: superuser_key
{{- end -}}
