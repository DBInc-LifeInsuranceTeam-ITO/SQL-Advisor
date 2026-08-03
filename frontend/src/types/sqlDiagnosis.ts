export interface DirectSqlMetricResponse {
  sqlId: string
  planHashValue?: number | null
  childNumber?: number | null
  parsingSchemaName?: string | null
  module?: string | null
  serviceName?: string | null
  executions?: number | null
  totalElapsedTimeSec?: number | null
  averageElapsedTimeSec?: number | null
  totalCpuTimeSec?: number | null
  averageCpuTimeSec?: number | null
  bufferGets?: number | null
  averageBufferGets?: number | null
  diskReads?: number | null
  averageDiskReads?: number | null
  rowsProcessed?: number | null
  lastActiveTime?: string | null
  sqlText?: string | null
}

export interface DirectSqlMetricListResponse {
  sourceView: string
  rows: DirectSqlMetricResponse[]
  warnings: string[]
}

export interface ExecutionPlanNodeResponse {
  id?: number | null
  parentId?: number | null
  depth?: number | null
  operation?: string | null
  options?: string | null
  objectOwner?: string | null
  objectName?: string | null
  objectType?: string | null
  cost?: number | null
  cardinality?: number | null
  bytes?: number | null
  accessPredicate?: string | null
  filterPredicate?: string | null
  actualRows?: number | null
  bufferGets?: number | null
  diskReads?: number | null
}

export interface StructuredExecutionPlanResponse {
  connectionId: number
  sqlId: string
  instanceId?: number | null
  childNumber?: number | null
  planHashValue?: number | null
  sourceView: string
  nodes: ExecutionPlanNodeResponse[]
  warnings: string[]
}

export interface IndexMetadataResponse {
  owner: string
  indexName: string
  indexType?: string | null
  uniqueness?: string | null
  status?: string | null
  visibility?: string | null
  numRows?: number | null
  distinctKeys?: number | null
  leafBlocks?: number | null
  clusteringFactor?: number | null
  lastAnalyzed?: string | null
  columns: string[]
  usedInCurrentPlan: boolean
}

export interface TableMetadataResponse {
  owner: string
  tableName: string
  numRows?: number | null
  blocks?: number | null
  avgRowLength?: number | null
  sampleSize?: number | null
  lastAnalyzed?: string | null
  partitioned?: string | null
  temporary?: string | null
  indexes: IndexMetadataResponse[]
}

export interface FindingResponse {
  code: string
  severity: string
  title: string
  description: string
  evidence: Record<string, unknown>
  recommendations: string[]
}

export interface SqlDiagnosisResponse {
  connectionId: number
  sqlId: string
  severity: string
  score: number
  summary: string
  metric: DirectSqlMetricResponse
  executionPlan: StructuredExecutionPlanResponse
  tableMetadata: {
    tables: TableMetadataResponse[]
    warnings: string[]
  }
  findings: FindingResponse[]
  warnings: string[]
}
