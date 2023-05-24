create_pblock pblock_qdma_subsystem
add_cells_to_pblock [get_pblocks pblock_qdma_subsystem] [get_cells -quiet top_i/xdma/xdma/inst]
resize_pblock [get_pblocks pblock_qdma_subsystem] -add {SLR0}

create_pblock pblock_cmac_subsystem
add_cells_to_pblock [get_pblocks pblock_cmac_subsystem] [get_cells -quiet top_i/cmac/cmac/inst]
resize_pblock [get_pblocks pblock_cmac_subsystem] -add {SLR1}
