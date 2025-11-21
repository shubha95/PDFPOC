package com.pdfpoc.pdfmodule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PdfPageContent(title: String, body: String) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text(text = title, fontSize = 22.sp)
        Text(text = body, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
    }
}