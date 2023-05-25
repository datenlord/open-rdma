create_pblock pblock_xdma
add_cells_to_pblock [get_pblocks pblock_xdma] [get_cells -quiet top_i/xdma/xdma/inst]
resize_pblock [get_pblocks pblock_xdma] -add {SLR0}

create_pblock pblock_cmac
add_cells_to_pblock [get_pblocks pblock_cmac] [get_cells -quiet top_i/cmac/cmac/inst]
resize_pblock [get_pblocks pblock_cmac] -add {SLR1}
