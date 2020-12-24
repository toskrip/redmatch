import { Box, Toolbar, Button, CircularProgress } from "@material-ui/core";
import React, { useState } from "react";
import { IParameters } from "@ahryman40k/ts-fhir-types/lib/R4";
import env from "@beam-australia/react-env";
import TextField from '@material-ui/core/TextField';
import { ApiError } from "./ApiError";
import { useAxios } from "../utils/hooks";

interface Props {
  projectId: string;
  onExportStarted: () => void;
  onExportFinished: () => void;
}

export default function Export(props: Props) {
  const { projectId, onExportStarted, onExportFinished } = props;
  const redmatchUrl = env("REDMATCH_URL");
  const [value, setValue] = useState('');
  const [status, setStatus] = useState('');
  const [error, setError] = useState<Error | null>(null);
  const axiosInstance = useAxios(redmatchUrl);

  const onExport = (projectId: string) => {
    fetchData(projectId)
      .then(() => {
        setStatus('');
      });
  };

  const fetchData = async (projectId: string) => {
    setStatus('loading');
    setValue('Transforming project in Redmatch...');

    let http = undefined;
    if (axiosInstance.current) {
      http = axiosInstance.current;
    } else {
      throw new Error('Undefined Axios current instance.');
    }

    try {
      await http.post<IParameters>(
        `${redmatchUrl}/project/${projectId}/$transform`,
        null,
        {
          headers: {
            "Accept": "application/json",
            "Content-Type": "application/json"
          }
        }
      );
      setValue(prev => prev + '\nTransformation was successful.\nDownloading file.');
    } catch (error) {
      const e : Error = { 
        name: 'Transformation error', 
        message: 'There was a problem with the transformation. Please check all mappings have been completed.'
      };
      setError(e);
      return;
    }

    // Download ZIP
    try {
      const { data: blobData } = await http({
        method: 'post',
        url: `${redmatchUrl}/project/${projectId}/$export`,
        responseType: 'blob',
        headers: {
          "Accept": "application/zip"
        }
      });

      const url = window.URL.createObjectURL(new Blob([blobData]));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `${projectId}.zip`);
      document.body.appendChild(link);
      link.click();
      if (link.parentNode) {
        link.parentNode.removeChild(link);
      }
    } catch (error) {
      const e : Error = { 
        name: 'Download error', 
        message: 'There was a problem downloading the file.'
      };
      setError(e);
    } finally {
      onExportFinished();
    }
  }

  return (
    <Box>
      <Toolbar>
        <Button
          type="submit"
          onClick={() => {
            onExportStarted();
            onExport(projectId);
          }}
          color="primary"
          endIcon={
            status === "loading" ? (
              <CircularProgress size={20} color="inherit" />
            ) : null
          }
        >
          Export
        </Button>
      </Toolbar>
      <TextField
        inputProps={{
          readOnly: true,
          disabled: true,
        }}
        variant="outlined"
        fullWidth={true}
        multiline={true}
        rows={30}
        rowsMax={30}
        aria-label="FHIR"
        value={value}
      />
      <ApiError error={error} />
    </Box>
  );
}
